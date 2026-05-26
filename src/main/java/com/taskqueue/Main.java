package com.taskqueue;

import com.taskqueue.server.TaskQueueServer;
import com.taskqueue.services.AIPrioritizationService;
import com.taskqueue.services.TaskExecutor;
import com.taskqueue.services.TaskService;
import com.taskqueue.utils.ApiConfig;
import com.taskqueue.utils.QueueProperties;

/**
 * Entry point for the Multi-Threaded Task Queue System.
 * Coordinates system bootstrap, prints visual ASCII art, and sets up graceful shutdown hooks.
 */
public class Main {
    public static void main(String[] args) {
        printBanner();
        QueueProperties config = QueueProperties.get();

        // 1. Initialize services
        System.out.println("[Bootstrap] Initializing Core System Components...");
        AIPrioritizationService aiService = new AIPrioritizationService();
        TaskExecutor taskExecutor = new TaskExecutor();
        
        TaskService taskService = new TaskService(
                config.getThreadPoolSize(),
                config.getQueueCapacity(),
                aiService,
                taskExecutor
        );

        // 2. Initialize HttpServer
        TaskQueueServer server = new TaskQueueServer(config.getServerPort(), taskService, aiService);

        // 3. Register Graceful Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Shutdown Hook] Initiating graceful system shutdown...");
            server.stop();
            taskService.shutdown();
            System.out.println("[Shutdown Hook] System terminated. Goodbye!");
        }, "Shutdown-Hook-Thread"));

        // 4. Start operations
        try {
            System.out.println("[Bootstrap] Starting Task Execution Engine & Thread Pool...");
            taskService.start();

            // TaskQueueServer start binds routes and begins listening
            System.out.println("[Bootstrap] Launching HTTP REST API & Web Dashboard...");
            server.start();

            System.out.println("\n==================================================================");
            System.out.println("  SYSTEM RUNNING SUCCESSFULLY!");
            int port = config.getServerPort();
            System.out.println("  - Interactive Web Dashboard : http://localhost:" + port + "/");
            System.out.println("  - API Tasks Endpoint       : http://localhost:" + port + "/api/tasks");
            System.out.println("  - Queue Statistics Endpoint : http://localhost:" + port + "/api/queue/stats");
            System.out.println();
            if (ApiConfig.isApiKeyConfigured()) {
                System.out.println("  - Groq AI API Mode          : ENABLED (llama-3.3-70b-versatile active)");
            } else {
                System.out.println("  - Groq AI API Mode          : FALLBACK ACTIVE (using deterministic type rules)");
                System.out.println("    [Hint] Set GROQ_API_KEY environment variable to enable AI prioritization.");
            }
            System.out.println("==================================================================\n");

        } catch (Exception e) {
            System.err.println("[Bootstrap] Critical failure during startup: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printBanner() {
        System.out.println("\n" +
                " █████╗ ██╗    ████████╗ █████╗ ███████╗██╗  ██╗\n" +
                "██╔══██╗██║    ╚══██╔══╝██╔══██╗██╔════╝██║  ██║\n" +
                "███████║██║       ██║   ███████║███████╗███████║\n" +
                "██╔══██║██║       ██║   ██╔══██║╚════██║██╔══██║\n" +
                "██║  ██║██║       ██║   ██║  ██║███████║██║  ██║\n" +
                "╚═╝  ╚═╝╚═╝       ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝\n" +
                "  ██████╗ ██╗   ██╗███████╗██╗   ██╗███████╗\n" +
                " ██╔═══██╗██║   ██║██╔════╝██║   ██║██╔════╝\n" +
                " ██║   ██║██║   ██║█████╗  ██║   ██║█████╗  \n" +
                " ██║▄▄ ██║██║   ██║██╔══╝  ██║   ██║██╔══╝  \n" +
                " ╚██████╔╝╚██████╔╝███████╗╚██████╔╝███████╗\n" +
                "  ╚════▀▀  ╚═════╝ ╚══════╝ ╚═════╝ ╚══════╝\n" +
                "================================================\n" +
                "   Multi-Threaded Queue System with Groq AI\n" +
                "================================================\n");
    }
}
