package com.taskqueue.server;

import com.sun.net.httpserver.HttpServer;
import com.taskqueue.handlers.AIInsightHandler;
import com.taskqueue.handlers.ConfigHandler;
import com.taskqueue.handlers.QueueHandler;
import com.taskqueue.handlers.StaticFileHandler;
import com.taskqueue.handlers.TaskHandler;
import com.taskqueue.services.AIPrioritizationService;
import com.taskqueue.services.TaskService;
import com.taskqueue.services.UiConfigService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Encapsulates the com.sun.net.httpserver.HttpServer setup, routing binds,
 * and port allocations.
 */
public class TaskQueueServer {
    private final int port;
    private final TaskService taskService;
    private final AIPrioritizationService aiService;
    private HttpServer server;

    public TaskQueueServer(int port, TaskService taskService, AIPrioritizationService aiService) {
        this.port = port;
        this.taskService = taskService;
        this.aiService = aiService;
    }

    /**
     * Instantiates the raw Java HttpServer, binds endpoints, and boots up.
     */
    public void start() throws IOException {
        // Create server instance listening on port
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        // Define route handlers
        TaskHandler taskHandler = new TaskHandler(taskService);
        QueueHandler queueHandler = new QueueHandler(taskService);
        AIInsightHandler aiHandler = new AIInsightHandler(taskService, aiService);
        UiConfigService uiConfigService = new UiConfigService(taskService);
        ConfigHandler configHandler = new ConfigHandler(uiConfigService, aiService);
        StaticFileHandler staticHandler = new StaticFileHandler();

        // Longest-prefix routes first
        server.createContext("/api/tasks/prioritize", aiHandler);
        server.createContext("/api/tasks/ai-insights", aiHandler);
        server.createContext("/api/tasks", taskHandler);
        server.createContext("/api/config/api-key/validate", configHandler);
        server.createContext("/api/config/api-key", configHandler);
        server.createContext("/api/config/ui", configHandler);
        server.createContext("/api/config/ai", configHandler);
        server.createContext("/api/queue/stats", queueHandler);
        server.createContext("/api/queue", queueHandler);
        server.createContext("/", staticHandler);

        // Use a simple built-in Executor for handling HTTP connections asynchronously
        server.setExecutor(Executors.newCachedThreadPool());

        // Start listening
        server.start();
        System.out.println("[Http Server] Successfully started server. Listening on port " + port);
        System.out.println("[Http Server] Interactive web dashboard available at: http://localhost:" + port + "/");
    }

    /**
     * Stops the HTTP server.
     */
    public void stop() {
        if (server != null) {
            System.out.println("Stopping HTTP Server...");
            server.stop(0);
        }
    }
}
