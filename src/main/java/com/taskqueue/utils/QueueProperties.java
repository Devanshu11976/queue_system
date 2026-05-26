package com.taskqueue.utils;

import com.taskqueue.models.TaskType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Loads runtime configuration from queue.properties (no hardcoded magic numbers in code).
 */
public final class QueueProperties {

    private static final QueueProperties INSTANCE = load();

    private final Properties props;

    private QueueProperties(Properties props) {
        this.props = props;
    }

    private static QueueProperties load() {
        Properties p = new Properties();
        try (InputStream in = QueueProperties.class.getClassLoader().getResourceAsStream("queue.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            System.err.println("[Config] Could not load queue.properties: " + e.getMessage());
        }
        applyEnvOverrides(p);
        return new QueueProperties(p);
    }

    private static void applyEnvOverrides(Properties p) {
        overrideInt(p, "server.port", "SERVER_PORT");
        override(p, "GROQ_API_KEY", null); // handled by ApiConfig
    }

    private static void override(Properties p, String envKey, String propKey) {
        String v = System.getenv(envKey);
        if (v != null && !v.isBlank() && propKey != null) {
            p.setProperty(propKey, v.trim());
        }
    }

    private static void overrideInt(Properties p, String propKey, String envKey) {
        String v = System.getenv(envKey);
        if (v != null && !v.isBlank()) {
            p.setProperty(propKey, v.trim());
        }
    }

    public static QueueProperties get() {
        return INSTANCE;
    }

    public int getServerPort() {
        return getInt("server.port", 8080);
    }

    public int getThreadPoolSize() {
        return getInt("thread.pool.size", 4);
    }

    public int getQueueCapacity() {
        return getInt("thread.pool.queue.capacity", 100);
    }

    public int getRefreshIntervalMs() {
        return getInt("ui.refresh.interval.ms", 1500);
    }

    public int getPriorityMin() {
        return getInt("task.priority.min", 1);
    }

    public int getPriorityMax() {
        return getInt("task.priority.max", 10);
    }

    public int getPriorityDefault() {
        return getInt("task.priority.default", 5);
    }

    public int getDeadlineMinSeconds() {
        return getInt("task.deadline.min.seconds", 10);
    }

    public int getDeadlineDefaultSeconds() {
        return getInt("task.deadline.default.seconds", 600);
    }

    public int getMaxRetries() {
        return getInt("task.max.retries", 3);
    }

    public List<String> getCancellableStatuses() {
        return Arrays.stream(get("task.cancellable.statuses", "PENDING,RUNNING").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public int getStatusSortWeight(String status) {
        return getInt("status.sort." + status, 0);
    }

    public int getFallbackPriority(TaskType type) {
        String key = "ai.fallback.priority." + type.name();
        if (props.containsKey(key)) {
            return getInt(key, getFallbackPriorityDefault());
        }
        return getFallbackPriorityDefault();
    }

    public int getFallbackPriorityDefault() {
        return getInt("ai.fallback.priority.default", 5);
    }

    public String getApiKeyPrefix() {
        return get("api.key.prefix", "gsk_");
    }

    public String getGroqConsoleUrl() {
        return get("api.groq.console.url", "https://console.groq.com/keys");
    }

    public String getUi(String key) {
        return get("ui." + key, "");
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
