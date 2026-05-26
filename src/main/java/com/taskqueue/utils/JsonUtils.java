package com.taskqueue.utils;

import com.taskqueue.models.Task;
import com.taskqueue.models.AIDecisionLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Utility class providing clean and simple JSON conversion helpers for models.
 */
public class JsonUtils {

    public static JSONObject taskToJson(Task task) {
        if (task == null) return new JSONObject();

        JSONObject json = new JSONObject();
        json.put("taskId", task.getTaskId().toString());
        json.put("name", task.getName());
        json.put("type", task.getType().name());
        json.put("payload", task.getPayload());
        json.put("priority", task.getPriority());
        json.put("status", task.getStatus().name());
        json.put("createdAt", task.getCreatedAt().toString());
        json.put("startedAt", task.getStartedAt() != null ? task.getStartedAt().toString() : null);
        json.put("completedAt", task.getCompletedAt() != null ? task.getCompletedAt().toString() : null);
        json.put("retryCount", task.getRetryCount());
        json.put("maxRetries", task.getMaxRetries());
        json.put("deadline", task.getDeadline() != null ? task.getDeadline().toString() : null);
        json.put("failureReason", task.getFailureReason());

        if (task.getAiDecision() != null) {
            json.put("aiDecision", aiDecisionToJson(task.getAiDecision()));
        } else {
            json.put("aiDecision", JSONObject.NULL);
        }

        return json;
    }

    public static JSONArray taskListToJson(Collection<Task> tasks) {
        JSONArray array = new JSONArray();
        for (Task task : tasks) {
            array.put(taskToJson(task));
        }
        return array;
    }

    public static JSONObject aiDecisionToJson(AIDecisionLog log) {
        if (log == null) return new JSONObject();

        JSONObject json = new JSONObject();
        json.put("decisionId", log.getDecisionId().toString());
        json.put("taskId", log.getTaskId().toString());
        json.put("originalPriority", log.getOriginalPriority());
        json.put("suggestedPriority", log.getSuggestedPriority());
        json.put("reasoning", log.getReasoning());
        json.put("estimatedWaitTime", log.getEstimatedWaitTime());
        json.put("queuePosition", log.getQueuePosition());
        json.put("warnings", new JSONArray(log.getWarnings()));
        json.put("recommendation", log.getRecommendation());
        json.put("timestamp", log.getTimestamp().toString());
        return json;
    }

    public static JSONArray aiDecisionListToJson(List<AIDecisionLog> logs) {
        JSONArray array = new JSONArray();
        for (AIDecisionLog log : logs) {
            array.put(aiDecisionToJson(log));
        }
        return array;
    }
}
