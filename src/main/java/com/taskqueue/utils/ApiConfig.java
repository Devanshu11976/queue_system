package com.taskqueue.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Manages Groq API credentials.
 * Priority: runtime (dashboard) → GROQ_API_KEY env → local.properties file.
 */
public final class ApiConfig {

    private static final String ENV_KEY = System.getenv("GROQ_API_KEY");
    private static final String LOCAL_FILE_KEY = loadKeyFromLocalProperties();
    private static volatile String runtimeApiKey;
    private static volatile boolean forceNoKey = false;

    private ApiConfig() {
    }

    public static void setForceNoKey(boolean force) {
        forceNoKey = force;
    }

    private static String loadKeyFromLocalProperties() {
        Properties props = new Properties();

        Path cwdFile = Path.of("local.properties");
        if (Files.isRegularFile(cwdFile)) {
            loadPropertiesFile(props, cwdFile);
        }

        Path projectFile = Path.of("queue-system", "local.properties");
        if (Files.isRegularFile(projectFile)) {
            loadPropertiesFile(props, projectFile);
        }

        try (InputStream in = ApiConfig.class.getClassLoader().getResourceAsStream("local.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // optional classpath local.properties
        }

        String key = props.getProperty("groq.api.key");
        if (key != null && !key.trim().isEmpty()) {
            System.out.println("[ApiConfig] Loaded Groq API key from local.properties");
            return key.trim();
        }
        return null;
    }

    private static void loadPropertiesFile(Properties props, Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[ApiConfig] Could not read " + path + ": " + e.getMessage());
        }
    }

    public static String getApiKey() {
        if (forceNoKey) {
            return null;
        }
        if (runtimeApiKey != null && !runtimeApiKey.trim().isEmpty()) {
            return runtimeApiKey.trim();
        }
        if (ENV_KEY != null && !ENV_KEY.trim().isEmpty()) {
            return ENV_KEY.trim();
        }
        return LOCAL_FILE_KEY;
    }

    public static boolean isApiKeyConfigured() {
        if (forceNoKey) {
            return false;
        }
        String key = getApiKey();
        return key != null && !key.isEmpty();
    }

    public static synchronized void setRuntimeApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            runtimeApiKey = null;
            return;
        }
        runtimeApiKey = apiKey.trim();
    }

    public static synchronized void clearRuntimeApiKey() {
        runtimeApiKey = null;
    }

    public static String getKeySource() {
        if (runtimeApiKey != null && !runtimeApiKey.isEmpty()) {
            return "runtime";
        }
        if (ENV_KEY != null && !ENV_KEY.trim().isEmpty()) {
            return "environment";
        }
        if (LOCAL_FILE_KEY != null && !LOCAL_FILE_KEY.isEmpty()) {
            return "local";
        }
        return "none";
    }

    public static String getPartialKeyHint() {
        String key = getApiKey();
        if (key == null || key.length() < 12) {
            return null;
        }
        if (key.length() <= 16) {
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }
        return key.substring(0, 12) + "..." + key.substring(key.length() - 4);
    }

    public static boolean hasEnvironmentKey() {
        return ENV_KEY != null && !ENV_KEY.trim().isEmpty();
    }

    public static boolean hasLocalFileKey() {
        return LOCAL_FILE_KEY != null && !LOCAL_FILE_KEY.isEmpty();
    }
}
