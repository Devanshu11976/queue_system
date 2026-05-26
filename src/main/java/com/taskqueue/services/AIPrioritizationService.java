package com.taskqueue.services;

import com.taskqueue.models.AIDecisionLog;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskType;
import com.taskqueue.utils.ApiConfig;
import com.taskqueue.utils.QueueProperties;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service that integrates with Groq API (LLaMA 3.3 70B) to dynamically prioritize tasks.
 * Uses caching, thread-safe memory logging, and robust deterministic fallbacks.
 */
public class AIPrioritizationService {
    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    private final HttpClient httpClient;
    private final List<AIDecisionLog> decisionLogs;
    private final ConcurrentHashMap<String, CacheEntry> cache;

    private final AtomicInteger apiCallCount = new AtomicInteger(0);
    private final AtomicInteger aiDecisionsMadeCount = new AtomicInteger(0);
    private final AtomicInteger urgentOverridesCount = new AtomicInteger(0);
    private volatile String lastApiError;

    // Cache structure
    private static class CacheEntry {
        final JSONObject decisionJson;
        final Instant timestamp;

        CacheEntry(JSONObject decisionJson) {
            this.decisionJson = decisionJson;
            this.timestamp = Instant.now();
        }

        boolean isExpired() {
            return Duration.between(timestamp, Instant.now()).getSeconds() > 30;
        }
    }

    public AIPrioritizationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.decisionLogs = Collections.synchronizedList(new ArrayList<>());
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Analyzes and prioritizes a new task. If API calls fail or credentials are missing,
     * it falls back to a rule-based priority model.
     */
    public void prioritizeTask(Task task, int currentQueueSize, int activeThreads, double completedRatePerMin) {
        TaskType type = task.getType();
        int originalPriority = task.getPriority();

        // 1. Caching Check
        String cacheKey = type.name() + "_" + (currentQueueSize / 5) + "_" + activeThreads;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            System.out.println("[AI Prioritization] Cache hit for key " + cacheKey + ". Applying cached AI prioritization.");
            applyDecision(task, cached.decisionJson, originalPriority);
            return;
        }

        // 2. Check if API Key is Configured
        if (!ApiConfig.isApiKeyConfigured()) {
            System.out.println("[AI Prioritization] Groq API Key is NOT configured in GROQ_API_KEY environment variable.");
            applyFallback(task, originalPriority, "API key not configured");
            return;
        }

        // 3. Invoke Groq API
        try {
            System.out.println("[AI Prioritization] Requesting Groq AI dynamic prioritization for task: " + task.getName());

            // Prepare prompt context
            JSONObject queueSummary = new JSONObject();
            queueSummary.put("queueSize", currentQueueSize);
            queueSummary.put("activeThreads", activeThreads);
            queueSummary.put("recentCompletionRatePerMin", completedRatePerMin);

            JSONObject taskSummary = new JSONObject();
            taskSummary.put("taskId", task.getTaskId().toString());
            taskSummary.put("name", task.getName());
            taskSummary.put("type", type.name());
            taskSummary.put("payload", task.getPayload());
            taskSummary.put("originalPriority", originalPriority);
            taskSummary.put("deadline", task.getDeadline().toString());
            taskSummary.put("secondsUntilDeadline", Duration.between(Instant.now(), task.getDeadline()).getSeconds());

            String systemPrompt = "You are a task queue optimization AI. Each task takes 15-50 seconds to execute. " +
                    "The queue has 4 worker threads. Use URGENT only when the deadline is under 5 minutes (300 seconds) away. " +
                    "Use NORMAL for deadlines 5-30 minutes away. Use DEFER for low-priority tasks with deadlines over 30 minutes. " +
                    "Respond ONLY with a JSON object. No markdown, no explanation.";

            String userPrompt = "Analyze this task and queue state. Respond ONLY in this exact JSON format:\n" +
                    "{\n" +
                    "  \"suggestedPriority\": (integer 1-10, where 10=critical, 1=lowest),\n" +
                    "  \"reasoning\": \"one line explanation\",\n" +
                    "  \"estimatedWaitTime\": (integer seconds until task starts),\n" +
                    "  \"queuePosition\": (estimated integer position in queue),\n" +
                    "  \"warnings\": [\"warning1\"],\n" +
                    "  \"recommendation\": \"URGENT\" or \"NORMAL\" or \"DEFER\"\n" +
                    "}\n\n" +
                    "URGENCY RULES (follow strictly):\n" +
                    "- URGENT: secondsUntilDeadline < 300 (less than 5 minutes)\n" +
                    "- NORMAL: secondsUntilDeadline is 300-1800 (5 to 30 minutes)\n" +
                    "- DEFER: secondsUntilDeadline > 1800 (more than 30 minutes) AND low business impact\n" +
                    "- PAYMENT tasks: always at least NORMAL, prefer higher suggestedPriority (8-10)\n" +
                    "- DATA_SYNC tasks: prefer DEFER unless deadline is tight\n\n" +
                    "New Task: " + taskSummary + "\n" +
                    "Current Queue: " + queueSummary + "\n" +
                    "Active Threads: " + activeThreads + "\n" +
                    "Recent Completion Rate: " + completedRatePerMin + " tasks/min.\n" +
                    "Respond with ONLY the JSON object.";

            // Assemble OpenAI-compatible request body
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", GROQ_MODEL);
            requestBody.put("max_tokens", 500);
            requestBody.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            requestBody.put("messages", messages);

            // Force JSON output
            requestBody.put("response_format", new JSONObject().put("type", "json_object"));

            String requestJson = requestBody.toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + ApiConfig.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(8))
                    .build();

            Instant apiStart = Instant.now();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Instant apiEnd = Instant.now();

            if (response.statusCode() == 200) {
                apiCallCount.incrementAndGet();
                JSONObject responseJson = new JSONObject(response.body());
                JSONArray choices = responseJson.getJSONArray("choices");
                String responseText = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content").trim();

                // Standardize output (sometimes models wrap in markdown ```json)
                if (responseText.startsWith("```")) {
                    responseText = responseText.substring(responseText.indexOf("{"), responseText.lastIndexOf("}") + 1);
                }

                JSONObject decisionJson = new JSONObject(responseText);

                // Cache the successful result
                cache.put(cacheKey, new CacheEntry(decisionJson));

                // Log token metrics
                JSONObject usage = responseJson.optJSONObject("usage");
                int inputTokens = usage != null ? usage.optInt("prompt_tokens", 0) : 0;
                int outputTokens = usage != null ? usage.optInt("completion_tokens", 0) : 0;
                System.out.printf("[AI Prioritization] Groq API Call completed in %d ms. Tokens: %d In, %d Out. (FREE)%n",
                        Duration.between(apiStart, apiEnd).toMillis(), inputTokens, outputTokens);

                applyDecision(task, decisionJson, originalPriority);
            } else {
                String apiError = parseGroqError(response.statusCode(), response.body());
                lastApiError = apiError;
                System.out.println("[AI Prioritization] Groq API error: " + apiError);
                applyFallback(task, originalPriority, apiError);
            }

        } catch (Exception e) {
            String apiError = e.getClass().getSimpleName() + ": " + e.getMessage();
            lastApiError = apiError;
            System.out.println("[AI Prioritization] Groq API integration encountered an exception: " + apiError);
            applyFallback(task, originalPriority, apiError);
        }
    }

    private static String parseGroqError(int statusCode, String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null && error.has("message")) {
                return error.getString("message");
            }
        } catch (Exception ignored) {
            // use generic message below
        }
        return "Groq API request failed (HTTP " + statusCode + ")";
    }

    public String getLastApiError() {
        return lastApiError;
    }

    private void applyDecision(Task task, JSONObject decisionJson, int originalPriority) {
        int suggestedPriority = decisionJson.getInt("suggestedPriority");
        String reasoning = decisionJson.getString("reasoning");
        int waitTime = decisionJson.getInt("estimatedWaitTime");
        int position = decisionJson.getInt("queuePosition");
        String recommendation = decisionJson.getString("recommendation");

        List<String> warnings = new ArrayList<>();
        JSONArray warningsArr = decisionJson.getJSONArray("warnings");
        for (int i = 0; i < warningsArr.length(); i++) {
            warnings.add(warningsArr.getString(i));
        }

        // Apply dynamic adjustments based on recommendation
        if ("URGENT".equalsIgnoreCase(recommendation)) {
            // Boost by 2 (capped at 10) instead of hard-coding 10, so tasks still differentiate
            suggestedPriority = Math.min(10, suggestedPriority + 2);
            urgentOverridesCount.incrementAndGet();
            System.out.println("[AI Prioritization] Boosting priority to " + suggestedPriority + " (URGENT) for task: " + task.getName());
        } else if ("DEFER".equalsIgnoreCase(recommendation)) {
            suggestedPriority = Math.max(1, suggestedPriority - 2);
            warnings.add("AI Recommendation deferred processing.");
            System.out.println("[AI Prioritization] Reducing priority to " + suggestedPriority + " (DEFER) for task: " + task.getName());
        }

        task.setPriority(suggestedPriority);

        // Standardize recommendation label based on the final priority for clear dashboard display
        if (suggestedPriority >= 8) {
            recommendation = "URGENT";
        } else if (suggestedPriority <= 3) {
            recommendation = "DEFER";
        } else {
            recommendation = "NORMAL";
        }

        AIDecisionLog decisionLog = new AIDecisionLog(
                task.getTaskId(),
                originalPriority,
                suggestedPriority,
                reasoning,
                waitTime,
                position,
                warnings,
                recommendation
        );

        task.setAiDecision(decisionLog);
        decisionLogs.add(decisionLog);
        aiDecisionsMadeCount.incrementAndGet();

        System.out.println("[AI Prioritization] Successfully prioritized task: " + task.getName() +
                " | Suggested Priority: " + suggestedPriority + " (" + recommendation + ") | Reason: " + reasoning);
    }

    private void applyFallback(Task task, int originalPriority, String reason) {
        int suggestedPriority = QueueProperties.get().getFallbackPriority(task.getType());

        task.setPriority(suggestedPriority);

        List<String> warnings = new ArrayList<>();
        warnings.add(reason);
        warnings.add("Using rule-based priority for " + task.getType() + " (see queue.properties).");

        String reasoning = "Groq AI unavailable. Applied fallback priority " + suggestedPriority
                + " for type " + task.getType() + ".";

        AIDecisionLog decisionLog = new AIDecisionLog(
                task.getTaskId(),
                originalPriority,
                suggestedPriority,
                reasoning,
                -1,
                -1,
                warnings,
                "FALLBACK"
        );

        task.setAiDecision(decisionLog);
        decisionLogs.add(decisionLog);
        aiDecisionsMadeCount.incrementAndGet();

        System.out.println("[AI Prioritization] Fallback Applied for " + task.getName() +
                ". Assigned static priority: " + suggestedPriority + " | Reason: " + reason);
    }

    public List<AIDecisionLog> getDecisionLogs() {
        return new ArrayList<>(decisionLogs);
    }

    public int getApiCallCount() {
        return apiCallCount.get();
    }

    public int getAiDecisionsMade() {
        return aiDecisionsMadeCount.get();
    }

    public int getUrgentOverridesCount() {
        return urgentOverridesCount.get();
    }

    /**
     * Runs AI/rule-based prioritization for a hypothetical task without enqueuing it.
     */
    public AIDecisionLog previewPrioritization(Task task, int currentQueueSize, int activeThreads, double completedRatePerMin) {
        prioritizeTask(task, currentQueueSize, activeThreads, completedRatePerMin);
        return task.getAiDecision();
    }
}
