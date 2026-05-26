package com.taskqueue.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.taskqueue.services.AIPrioritizationService;
import com.taskqueue.services.UiConfigService;
import com.taskqueue.utils.ApiConfig;
import com.taskqueue.utils.QueueProperties;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * API key configuration for the dashboard.
 * - GET  /api/config/ai           → status (configured, source, hint)
 * - POST /api/config/api-key      → set runtime key (in-memory only)
 * - DELETE /api/config/api-key    → clear runtime key
 * - POST /api/config/api-key/validate → test key against Groq Chat Completions API
 */
public class ConfigHandler implements HttpHandler {

    private static final String VALIDATE_URL = "https://api.groq.com/openai/v1/chat/completions";
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final UiConfigService uiConfigService;
    private final AIPrioritizationService aiService;

    public ConfigHandler(UiConfigService uiConfigService, AIPrioritizationService aiService) {
        this.uiConfigService = uiConfigService;
        this.aiService = aiService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            addCors(exchange);

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/config/ui".equals(path)) {
                sendJson(exchange, 200, uiConfigService.getUiConfig());
            } else if ("GET".equalsIgnoreCase(method) && "/api/config/ai".equals(path)) {
                handleGetStatus(exchange);
            } else if ("POST".equalsIgnoreCase(method) && "/api/config/api-key".equals(path)) {
                handleSetKey(exchange);
            } else if ("POST".equalsIgnoreCase(method) && "/api/config/api-key/validate".equals(path)) {
                handleValidateKey(exchange);
            } else if ("DELETE".equalsIgnoreCase(method) && "/api/config/api-key".equals(path)) {
                handleClearKey(exchange);
            } else {
                sendJson(exchange, 404, errorJson("Endpoint not found."));
            }
        } catch (Exception e) {
            sendJson(exchange, 500, errorJson("Internal error: " + e.getMessage()));
        }
    }

    private void handleGetStatus(HttpExchange exchange) throws IOException {
        JSONObject body = new JSONObject();
        body.put("configured", ApiConfig.isApiKeyConfigured());
        body.put("source", ApiConfig.getKeySource());
        body.put("partial_key_hint", ApiConfig.getPartialKeyHint() != null ? ApiConfig.getPartialKeyHint() : JSONObject.NULL);
        body.put("status", ApiConfig.isApiKeyConfigured() ? "active" : "inactive");
        body.put("environment_key_present", ApiConfig.hasEnvironmentKey());
        body.put("type", "api_key");
        body.put("message", ApiConfig.isApiKeyConfigured()
                ? "Groq API key is configured. AI prioritization is enabled."
                : "No API key set. Add your key from console.groq.com or set GROQ_API_KEY.");
        String lastError = aiService.getLastApiError();
        body.put("last_error", lastError != null ? lastError : JSONObject.NULL);
        body.put("billing_blocked", lastError != null && lastError.toLowerCase().contains("credit"));
        sendJson(exchange, 200, body);
    }

    private void handleSetKey(HttpExchange exchange) throws IOException {
        String raw = readBody(exchange);
        if (raw.trim().isEmpty()) {
            sendJson(exchange, 400, errorJson("Request body required."));
            return;
        }

        JSONObject req = new JSONObject(raw);
        if (!req.has("api_key") && !req.has("apiKey")) {
            sendJson(exchange, 400, errorJson("Missing field: api_key"));
            return;
        }

        String key = req.has("api_key") ? req.getString("api_key") : req.getString("apiKey");
        if (key == null || key.trim().isEmpty()) {
            sendJson(exchange, 400, errorJson("API key cannot be empty."));
            return;
        }

        String prefix = QueueProperties.get().getApiKeyPrefix();
        if (!key.startsWith(prefix)) {
            sendJson(exchange, 400, errorJson("Invalid Groq API key format. Keys must start with " + prefix));
            return;
        }

        ApiConfig.setRuntimeApiKey(key);

        JSONObject body = new JSONObject();
        body.put("message", "API key saved in memory for this server session.");
        body.put("configured", true);
        body.put("source", ApiConfig.getKeySource());
        body.put("partial_key_hint", ApiConfig.getPartialKeyHint());
        body.put("status", "active");
        sendJson(exchange, 200, body);
    }

    private void handleClearKey(HttpExchange exchange) throws IOException {
        ApiConfig.clearRuntimeApiKey();
        JSONObject body = new JSONObject();
        body.put("message", "Runtime API key cleared.");
        body.put("configured", ApiConfig.isApiKeyConfigured());
        body.put("source", ApiConfig.getKeySource());
        body.put("status", ApiConfig.isApiKeyConfigured() ? "active" : "inactive");
        sendJson(exchange, 200, body);
    }

    private void handleValidateKey(HttpExchange exchange) throws IOException {
        String raw = readBody(exchange);
        String keyToTest = null;

        if (raw != null && !raw.trim().isEmpty()) {
            JSONObject req = new JSONObject(raw);
            if (req.has("api_key")) {
                keyToTest = req.getString("api_key");
            } else if (req.has("apiKey")) {
                keyToTest = req.getString("apiKey");
            }
        }

        if (keyToTest == null || keyToTest.trim().isEmpty()) {
            keyToTest = ApiConfig.getApiKey();
        }

        if (keyToTest == null || keyToTest.trim().isEmpty()) {
            sendJson(exchange, 400, errorJson("No API key to validate. Provide api_key in body or configure one first."));
            return;
        }

        try {
            JSONObject testBody = new JSONObject();
            testBody.put("model", "llama-3.1-8b-instant");
            testBody.put("max_tokens", 5);
            testBody.put("messages", new org.json.JSONArray()
                    .put(new JSONObject()
                            .put("role", "user")
                            .put("content", "hello")));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VALIDATE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + keyToTest.trim())
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(testBody.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject body = new JSONObject();
            if (response.statusCode() == 200) {
                body.put("valid", true);
                body.put("status", "active");
                body.put("message", "API key is valid. Groq API accepted the key.");
                JSONObject usage = new JSONObject(response.body()).optJSONObject("usage");
                if (usage != null) {
                    body.put("input_tokens", usage.optInt("prompt_tokens", 0));
                    body.put("output_tokens", usage.optInt("completion_tokens", 0));
                }
                sendJson(exchange, 200, body);
            } else {
                body.put("valid", false);
                body.put("status", "inactive");
                body.put("message", "API key validation failed (HTTP " + response.statusCode() + ").");
                body.put("detail", response.body());
                sendJson(exchange, 400, body);
            }
        } catch (Exception e) {
            JSONObject body = new JSONObject();
            body.put("valid", false);
            body.put("status", "inactive");
            body.put("message", "Validation request failed: " + e.getMessage());
            sendJson(exchange, 400, body);
        }
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private static JSONObject errorJson(String message) {
        JSONObject o = new JSONObject();
        o.put("error", message);
        o.put("status", "ERROR");
        return o;
    }

    private static void sendJson(HttpExchange exchange, int code, JSONObject body) throws IOException {
        sendJson(exchange, code, body.toString());
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
