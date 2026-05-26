package com.taskqueue.services;

import com.taskqueue.models.TaskStatus;
import com.taskqueue.models.TaskType;
import com.taskqueue.utils.ApiConfig;
import com.taskqueue.utils.QueueProperties;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds dashboard configuration from queue.properties and live server state.
 */
public class UiConfigService {

    private final TaskService taskService;
    private final QueueProperties config;

    public UiConfigService(TaskService taskService) {
        this.taskService = taskService;
        this.config = QueueProperties.get();
    }

    public JSONObject getUiConfig() {
        JSONObject root = new JSONObject();

        JSONObject server = new JSONObject();
        server.put("port", config.getServerPort());
        server.put("baseUrl", "http://localhost:" + config.getServerPort());
        root.put("server", server);

        JSONObject pool = new JSONObject();
        pool.put("threadPoolSize", taskService.getThreadPool().getThreadCount());
        pool.put("maxQueueCapacity", taskService.getThreadPool().getMaxQueueCapacity());
        pool.put("refreshIntervalMs", config.getRefreshIntervalMs());
        root.put("threadPool", pool);

        root.put("taskTypes", buildTaskTypes());
        root.put("taskStatuses", buildStatusArray());
        root.put("cancellableStatuses", new JSONArray(config.getCancellableStatuses()));
        root.put("statusSortOrder", buildStatusSortOrder());

        JSONObject priority = new JSONObject();
        priority.put("min", config.getPriorityMin());
        priority.put("max", config.getPriorityMax());
        priority.put("default", config.getPriorityDefault());
        root.put("priority", priority);

        JSONObject deadline = new JSONObject();
        deadline.put("minSeconds", config.getDeadlineMinSeconds());
        deadline.put("defaultSeconds", config.getDeadlineDefaultSeconds());
        root.put("deadline", deadline);

        root.put("maxRetries", config.getMaxRetries());

        JSONObject form = new JSONObject();
        form.put("namePlaceholder", config.getUi("form.namePlaceholder"));
        form.put("payloadPlaceholder", config.getUi("form.payloadPlaceholder"));
        form.put("apiKeyPlaceholder", config.getUi("form.apiKeyPlaceholder"));
        form.put("apiKeyPrefix", config.getApiKeyPrefix());
        form.put("anthropicConsoleUrl", config.getGroqConsoleUrl());
        root.put("form", form);

        root.put("labels", buildLabels());
        root.put("messages", buildMessages());
        root.put("statsDefinitions", buildStatsDefinitions());

        JSONObject ai = new JSONObject();
        ai.put("configured", ApiConfig.isApiKeyConfigured());
        ai.put("source", ApiConfig.getKeySource());
        ai.put("partialKeyHint", ApiConfig.getPartialKeyHint() != null ? ApiConfig.getPartialKeyHint() : JSONObject.NULL);
        ai.put("status", ApiConfig.isApiKeyConfigured() ? "active" : "inactive");
        ai.put("preconfigured", ApiConfig.isApiKeyConfigured() && !"runtime".equals(ApiConfig.getKeySource()));
        String lastErr = taskService.getAiService().getLastApiError();
        ai.put("last_error", lastErr != null ? lastErr : JSONObject.NULL);
        ai.put("billing_blocked", lastErr != null && lastErr.toLowerCase().contains("credit"));
        root.put("ai", ai);

        return root;
    }

    private JSONObject buildLabels() {
        JSONObject labels = new JSONObject();
        labels.put("appTitle", config.getUi("app.title"));
        labels.put("appSubtitle", config.getUi("app.subtitle"));
        labels.put("brandIcon", config.getUi("brand.icon"));
        labels.put("apiSettings", config.getUi("button.apiSettings"));
        labels.put("submit", config.getUi("button.submit"));
        labels.put("preview", config.getUi("button.preview"));
        labels.put("validateKey", config.getUi("button.validateKey"));
        labels.put("saveKey", config.getUi("button.saveKey"));
        labels.put("clearKey", config.getUi("button.clearKey"));
        labels.put("cancelTask", config.getUi("button.cancelTask"));
        labels.put("panelSubmit", config.getUi("panel.submit"));
        labels.put("panelPreview", config.getUi("panel.preview"));
        labels.put("panelPreviewHint", config.getUi("panel.previewHint"));
        labels.put("panelBoard", config.getUi("panel.board"));
        labels.put("panelAiLogs", config.getUi("panel.aiLogs"));
        labels.put("name", config.getUi("label.name"));
        labels.put("type", config.getUi("label.type"));
        labels.put("payload", config.getUi("label.payload"));
        labels.put("priority", config.getUi("label.priority"));
        labels.put("deadline", config.getUi("label.deadline"));
        labels.put("apiKey", config.getUi("label.apiKey"));
        labels.put("keyStatus", config.getUi("label.keyStatus"));
        labels.put("keySource", config.getUi("label.keySource"));
        labels.put("keyHint", config.getUi("label.keyHint"));
        labels.put("settingsTitle", config.getUi("settings.title"));
        labels.put("settingsDesc", config.getUi("settings.desc"));
        labels.put("taskPriority", config.getUi("task.priority"));
        labels.put("taskRetries", config.getUi("task.retries"));
        labels.put("taskDeadline", config.getUi("task.deadline"));
        labels.put("aiProvider", config.getUi("task.aiProvider"));
        labels.put("taskCount", config.getUi("task.count"));
        return labels;
    }

    private JSONObject buildMessages() {
        JSONObject messages = new JSONObject();
        messages.put("emptyTasks", config.getUi("msg.emptyTasks"));
        messages.put("emptyAiLogs", config.getUi("msg.emptyAiLogs"));
        messages.put("loadingTasks", config.getUi("msg.loadingTasks"));
        messages.put("apiKeyNotSet", config.getUi("msg.apiKeyNotSet"));
        messages.put("apiKeyActive", config.getUi("msg.apiKeyActive"));
        messages.put("previewRequiresFields", config.getUi("msg.previewRequiresFields"));
        messages.put("submitRequiresFields", config.getUi("msg.submitRequiresFields"));
        messages.put("apiKeyRequired", config.getUi("msg.apiKeyRequired"));
        messages.put("apiKeyPreconfigured", config.getUi("msg.apiKeyPreconfigured"));
        messages.put("aiBillingBlocked", config.getUi("msg.aiBillingBlocked"));
        messages.put("priorityRange", config.getUi("msg.priorityRange"));
        messages.put("deadlineMin", config.getUi("msg.deadlineMin"));
        messages.put("cancelConfirm", config.getUi("msg.cancelConfirm"));
        messages.put("configLoadFailed", config.getUi("msg.configLoadFailed"));
        messages.put("submitSuccess", config.getUi("msg.submitSuccess"));
        messages.put("aiLogWait", config.getUi("msg.aiLogWait"));
        return messages;
    }

    private JSONArray buildStatsDefinitions() {
        JSONArray arr = new JSONArray();
        arr.put(statDef("stat-threads", config.getUi("stat.threads"), "threads"));
        arr.put(statDef("stat-queue", config.getUi("stat.queue"), "queue"));
        arr.put(statDef("stat-pending", config.getUi("stat.pending"), "number", "pendingTasks"));
        arr.put(statDef("stat-running", config.getUi("stat.running"), "number", "runningTasks"));
        arr.put(statDef("stat-completed", config.getUi("stat.completed"), "number", "completedTasks", "stat-card--ok"));
        arr.put(statDef("stat-failed", config.getUi("stat.failed"), "failedCancelled", "stat-card--warn"));
        arr.put(statDef("stat-ai", config.getUi("stat.ai"), "ai", "stat-card--ai"));
        return arr;
    }

    private JSONObject statDef(String id, String label, String format) {
        return statDef(id, label, format, null, null);
    }

    private JSONObject statDef(String id, String label, String format, String field) {
        return statDef(id, label, format, field, null);
    }

    private JSONObject statDef(String id, String label, String format, String field, String cssClass) {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        o.put("format", format);
        if (field != null) {
            o.put("field", field);
        }
        if (cssClass != null) {
            o.put("cssClass", cssClass);
        }
        return o;
    }

    private JSONArray buildTaskTypes() {
        JSONArray types = new JSONArray();
        for (TaskType type : TaskType.values()) {
            int[] range = TaskExecutor.getSimulationDurationSeconds(type);
            JSONObject item = new JSONObject();
            item.put("value", type.name());
            item.put("label", type.name());
            item.put("durationMinSeconds", range[0]);
            item.put("durationMaxSeconds", range[1]);
            types.put(item);
        }
        return types;
    }

    private JSONArray buildStatusArray() {
        JSONArray arr = new JSONArray();
        for (TaskStatus status : TaskStatus.values()) {
            arr.put(status.name());
        }
        return arr;
    }

    private JSONObject buildStatusSortOrder() {
        JSONObject json = new JSONObject();
        for (TaskStatus status : TaskStatus.values()) {
            json.put(status.name(), config.getStatusSortWeight(status.name()));
        }
        return json;
    }
}
