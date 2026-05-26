package com.taskqueue.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.taskqueue.exceptions.InvalidTaskException;
import com.taskqueue.exceptions.TaskNotFoundException;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskStatus;
import com.taskqueue.models.TaskType;
import com.taskqueue.services.TaskService;
import com.taskqueue.utils.QueueProperties;
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
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles Task-related REST API operations.
 * Maps endpoints:
 * - POST   /api/tasks              (Submit new task)
 * - GET    /api/tasks              (List all tasks)
 * - GET    /api/tasks/{id}         (Get task details)
 * - DELETE /api/tasks/{id}         (Cancel task)
 * - GET    /api/tasks/status/{s}   (Filter by status)
 */
public class TaskHandler implements HttpHandler {
    private final TaskService taskService;

    public TaskHandler(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        System.out.println("[Task Handler] Received " + method + " request on: " + path);

        try {
            // Enable CORS headers for easy development/testing integration
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/tasks".equals(path)) {
                handleSubmitTask(exchange);
            } else if ("GET".equalsIgnoreCase(method) && "/api/tasks".equals(path)) {
                handleListTasks(exchange);
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/tasks/status/")) {
                handleFilterTasksByStatus(exchange, path);
            } else if (path.startsWith("/api/tasks/")) {
                // Check if path is /api/tasks/{id}
                String idStr = path.substring("/api/tasks/".length());
                if (idStr.contains("/")) {
                    sendError(exchange, 404, "Endpoint not found.");
                } else {
                    handleSingleTaskOperations(exchange, method, idStr);
                }
            } else {
                sendError(exchange, 404, "Endpoint not found.");
            }
        } catch (Exception e) {
            System.err.println("[Task Handler] Internal Error: " + e.getMessage());
            e.printStackTrace();
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleSubmitTask(HttpExchange exchange) throws IOException {
        try {
            String body = getRequestBody(exchange);
            if (body.trim().isEmpty()) {
                sendError(exchange, 400, "Request body cannot be empty.");
                return;
            }

            JSONObject json = new JSONObject(body);

            // Required parameters
            if (!json.has("name") || !json.has("type") || !json.has("payload")
                    || !json.has("priority") || !json.has("deadlineSeconds")) {
                sendError(exchange, 400, "Required parameters: name, type, payload, priority, deadlineSeconds.");
                return;
            }

            String name = json.getString("name");
            String typeStr = json.getString("type");
            String payload = json.getString("payload");

            QueueProperties config = QueueProperties.get();
            int basePriority = json.getInt("priority");
            int deadlineSeconds = json.getInt("deadlineSeconds");
            if (deadlineSeconds < config.getDeadlineMinSeconds()) {
                sendError(exchange, 400, "deadlineSeconds must be at least " + config.getDeadlineMinSeconds() + ".");
                return;
            }

            TaskType type;
            try {
                type = TaskType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, "Invalid task type. Supported types: EMAIL, REPORT, PAYMENT, NOTIFICATION, DATA_SYNC");
                return;
            }

            Instant deadline = Instant.now().plus(deadlineSeconds, ChronoUnit.SECONDS);

            // Submits to execution queue through Service coordinate
            Task task = taskService.createTask(name, type, payload, deadline, basePriority);
            
            JSONObject response = JsonUtils.taskToJson(task);
            sendResponse(exchange, 201, response.toString());

        } catch (InvalidTaskException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to submit task: " + e.getMessage());
        }
    }

    private void handleListTasks(HttpExchange exchange) throws IOException {
        Collection<Task> tasks = taskService.getAllTasks();
        JSONArray responseArray = JsonUtils.taskListToJson(tasks);
        sendResponse(exchange, 200, responseArray.toString());
    }

    private void handleFilterTasksByStatus(HttpExchange exchange, String path) throws IOException {
        String statusStr = path.substring("/api/tasks/status/".length()).toUpperCase();
        try {
            TaskStatus status = TaskStatus.valueOf(statusStr);
            Collection<Task> filtered = taskService.getTasksByStatus(status);
            JSONArray responseArray = JsonUtils.taskListToJson(filtered);
            sendResponse(exchange, 200, responseArray.toString());
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid task status: " + statusStr);
        }
    }

    private void handleSingleTaskOperations(HttpExchange exchange, String method, String idStr) throws IOException {
        UUID taskId;
        try {
            taskId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid task UUID format.");
            return;
        }

        try {
            if ("GET".equalsIgnoreCase(method)) {
                Task task = taskService.getTask(taskId);
                JSONObject response = JsonUtils.taskToJson(task);
                sendResponse(exchange, 200, response.toString());
            } else if ("DELETE".equalsIgnoreCase(method)) {
                taskService.cancelTask(taskId);
                JSONObject response = new JSONObject();
                response.put("message", "Task " + taskId + " cancelled successfully.");
                response.put("status", "SUCCESS");
                sendResponse(exchange, 200, response.toString());
            } else {
                sendError(exchange, 405, "Method not supported for specific task operations.");
            }
        } catch (TaskNotFoundException e) {
            sendError(exchange, 404, e.getMessage());
        }
    }

    // Helper utilities for raw HTTP servers
    private String getRequestBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
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
