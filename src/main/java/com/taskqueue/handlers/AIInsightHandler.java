package com.taskqueue.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.taskqueue.models.AIDecisionLog;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskStatus;
import com.taskqueue.models.TaskType;
import com.taskqueue.services.AIPrioritizationService;
import com.taskqueue.services.TaskService;
import com.taskqueue.utils.JsonUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles endpoints relating to AI dynamic insights:
 * - POST /api/tasks/prioritize (trigger AI priority optimization on an existing pending task)
 * - GET  /api/tasks/ai-insights  (fetch list of all AI decision records)
 */
public class AIInsightHandler implements HttpHandler {
    private final TaskService taskService;
    private final AIPrioritizationService aiService;

    public AIInsightHandler(TaskService taskService, AIPrioritizationService aiService) {
        this.taskService = taskService;
        this.aiService = aiService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            // Enable CORS headers
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/tasks/ai-insights".equals(path)) {
                handleGetAiInsights(exchange);
            } else if ("POST".equalsIgnoreCase(method) && "/api/tasks/prioritize".equals(path)) {
                handleTriggerPrioritize(exchange);
            } else {
                sendError(exchange, 404, "Endpoint not found.");
            }
        } catch (Exception e) {
            System.err.println("[AI Handler] Error: " + e.getMessage());
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleGetAiInsights(HttpExchange exchange) throws IOException {
        List<AIDecisionLog> logs = aiService.getDecisionLogs();
        JSONArray jsonArray = new JSONArray();

        // Retrieve and sort currently queued tasks to get live positions
        List<Task> sortedQueue = taskService.getThreadPool().getQueuedTasks();
        Collections.sort(sortedQueue);

        for (AIDecisionLog log : logs) {
            JSONObject obj = JsonUtils.aiDecisionToJson(log);
            try {
                Task task = taskService.getTask(log.getTaskId());
                obj.put("taskName", task.getName());
                obj.put("taskStatus", task.getStatus().name());

                // For PENDING tasks, compute actual queue position and estimated wait time dynamically
                if (task.getStatus() == TaskStatus.PENDING) {
                    int livePosition = sortedQueue.indexOf(task) + 1;
                    if (livePosition > 0) {
                        obj.put("queuePosition", livePosition);

                        double completionRate = taskService.getRecentCompletionRate(); // tasks per minute
                        double avgDurationSeconds = 25.0; // average execution duration per task
                        int activeThreads = taskService.getThreadPool().getActiveThreadCount();
                        int poolSize = taskService.getThreadPool().getThreadCount();
                        int threadsToUse = Math.max(1, poolSize);

                        double estimatedWait;
                        if (completionRate > 0.1) {
                            estimatedWait = (livePosition - 1) * (60.0 / completionRate);
                        } else {
                            estimatedWait = (livePosition - 1) * (avgDurationSeconds / threadsToUse);
                        }
                        if (activeThreads > 0) {
                            estimatedWait += 5.0; // add base wait for executing tasks to wrap up
                        }
                        obj.put("estimatedWaitTime", Math.max(0, (int) Math.round(estimatedWait)));
                    } else {
                        obj.put("queuePosition", 1);
                        obj.put("estimatedWaitTime", 0);
                    }
                } else if (task.getStatus() == TaskStatus.RUNNING) {
                    obj.put("queuePosition", 0);
                    obj.put("estimatedWaitTime", 0);
                } else {
                    // COMPLETED, FAILED, CANCELLED
                    obj.put("queuePosition", 0);
                    obj.put("estimatedWaitTime", 0);
                }
            } catch (Exception e) {
                obj.put("taskName", "Preview Task");
                obj.put("taskStatus", "PREVIEW");
            }
            jsonArray.put(obj);
        }
        sendResponse(exchange, 200, jsonArray.toString());
    }

    private void handleTriggerPrioritize(HttpExchange exchange) throws IOException {
        try {
            String body = getRequestBody(exchange);
            if (body.trim().isEmpty()) {
                sendError(exchange, 400, "Request body required.");
                return;
            }

            JSONObject requestJson = new JSONObject(body);
            int queueSize = taskService.getThreadPool().getQueueSize();
            int activeThreads = taskService.getThreadPool().getActiveThreadCount();
            double completionRate = taskService.getRecentCompletionRate();

            AIDecisionLog decision;

            if (requestJson.has("taskId")) {
                UUID taskId = UUID.fromString(requestJson.getString("taskId"));
                Task task = taskService.getTask(taskId);

                if (task.getStatus() != TaskStatus.PENDING) {
                    sendError(exchange, 400, "AI prioritization on existing tasks requires PENDING status. Current: " + task.getStatus());
                    return;
                }

                aiService.prioritizeTask(task, queueSize, activeThreads, completionRate);
                decision = task.getAiDecision();

                JSONObject response = new JSONObject();
                response.put("message", "AI prioritization applied to pending task.");
                response.put("taskId", task.getTaskId().toString());
                response.put("newPriority", task.getPriority());
                response.put("aiDecision", JsonUtils.aiDecisionToJson(decision));
                sendResponse(exchange, 200, response.toString());
                return;
            }

            if (!requestJson.has("name") || !requestJson.has("type") || !requestJson.has("payload")
                    || !requestJson.has("priority") || !requestJson.has("deadlineSeconds")) {
                sendError(exchange, 400, "Provide taskId OR name, type, payload, priority, and deadlineSeconds.");
                return;
            }

            TaskType type = TaskType.valueOf(requestJson.getString("type").toUpperCase());
            int basePriority = requestJson.getInt("priority");
            int deadlineSeconds = requestJson.getInt("deadlineSeconds");
            Instant deadline = Instant.now().plus(deadlineSeconds, ChronoUnit.SECONDS);

            Task previewTask = new Task(
                    requestJson.getString("name"),
                    type,
                    requestJson.getString("payload"),
                    deadline,
                    basePriority
            );

            decision = aiService.previewPrioritization(previewTask, queueSize, activeThreads, completionRate);

            JSONObject response = new JSONObject();
            response.put("message", "AI priority preview completed (task not enqueued).");
            response.put("suggestedPriority", previewTask.getPriority());
            response.put("aiDecision", JsonUtils.aiDecisionToJson(decision));
            sendResponse(exchange, 200, response.toString());
            return;

        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid UUID format.");
        } catch (com.taskqueue.exceptions.TaskNotFoundException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to run prioritization: " + e.getMessage());
        }
    }

    private String getRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JSONObject errorJson = new JSONObject();
        errorJson.put("error", message);
        errorJson.put("status", "ERROR");
        sendResponse(exchange, statusCode, errorJson.toString());
    }
}
