package com.taskqueue.services;

import com.taskqueue.exceptions.TaskExecutionException;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskStatus;

import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Thread-safe execution engine that simulates processing logic, logs execution flow,
 * and schedules exponential backoff retries on failure.
 */
public class TaskExecutor {
    private final Timer retryTimer;
    private Consumer<Task> requeueCallback;

    public TaskExecutor() {
        // Create a daemon timer so it does not block application shutdown
        this.retryTimer = new Timer("TaskRetryScheduler", true);
    }

    /**
     * Registers the re-queuing callback to avoid circular package references.
     */
    public void setRequeueCallback(Consumer<Task> requeueCallback) {
        this.requeueCallback = requeueCallback;
    }

    /**
     * Executes the task by simulating processing time and managing the lifecycle state.
     */
    public void execute(Task task) {
        synchronized (task) {
            if (task.getStatus() == TaskStatus.CANCELLED) {
                return;
            }
        }

        task.setStartedAt(Instant.now());
        task.setStatus(TaskStatus.RUNNING);

        long sleepDurationMs = getSimulationDurationMs(task);
        System.out.println("[Task Executor] Thread '" + Thread.currentThread().getName() +
                "' started processing task '" + task.getName() + "' (ID: " + task.getTaskId() +
                "). Estimated duration: " + (sleepDurationMs / 1000.0) + "s");

        try {
            // Simulate work
            Thread.sleep(sleepDurationMs);

            // Simulate execution errors to test the retry mechanism:
            // Fail if payload contains "fail", "error", or randomly (5% chance)
            String payloadLower = task.getPayload().toLowerCase();
            if (payloadLower.contains("fail") || payloadLower.contains("error")) {
                throw new TaskExecutionException("Simulated failure from payload trigger.");
            }

            // Successful completion
            task.setCompletedAt(Instant.now());
            task.setStatus(TaskStatus.COMPLETED);
            System.out.println("[Task Executor] Successfully completed task: '" + task.getName() + "'");

        } catch (InterruptedException e) {
            // Task execution interrupted (e.g. shutdown)
            task.setFailureReason("Thread interrupted during shutdown.");
            task.setStatus(TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            System.out.println("[Task Executor] Task execution interrupted: '" + task.getName() + "'");
            Thread.currentThread().interrupt(); // Restore status

        } catch (Exception e) {
            // Process failure and manage retries
            handleFailure(task, e.getMessage());
        }
    }

    private void handleFailure(Task task, String errorMsg) {
        task.incrementRetryCount();
        task.setFailureReason(errorMsg);
        int currentRetry = task.getRetryCount();
        int maxRetries = task.getMaxRetries();

        System.out.println("[Task Executor] Task '" + task.getName() + "' failed: " + errorMsg +
                " (Retry " + currentRetry + "/" + maxRetries + ")");

        if (currentRetry < maxRetries) {
            // Exponential backoff: delay = 2^retryCount seconds
            long delaySeconds = (long) Math.pow(2, currentRetry);
            task.setStatus(TaskStatus.PENDING); // Mark as pending again while waiting

            System.out.println("[Task Executor] Scheduling Task '" + task.getName() +
                    "' for re-queuing in " + delaySeconds + " seconds (Exponential Backoff)...");

            retryTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (requeueCallback != null) {
                        try {
                            System.out.println("[Task Executor] Re-submitting task '" + task.getName() + "' after backoff delay.");
                            requeueCallback.accept(task);
                        } catch (Exception e) {
                            System.err.println("[Task Executor] Failed to re-queue task: " + e.getMessage());
                        }
                    }
                }
            }, delaySeconds * 1000L);

        } else {
            // Exhausted all retries
            task.setStatus(TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            System.out.println("[Task Executor] Max retries (" + maxRetries + ") reached. Task '" +
                    task.getName() + "' failed definitively.");
        }
    }

    private long getSimulationDurationMs(Task task) {
        int[] sec = getSimulationDurationSeconds(task.getType());
        long minMs = sec[0] * 1000L;
        long maxMs = sec[1] * 1000L;
        if (maxMs <= minMs) {
            return minMs;
        }
        return ThreadLocalRandom.current().nextLong(minMs, maxMs);
    }

    /** Min/max simulated duration in seconds per type (exposed to UI config API). */
    public static int[] getSimulationDurationSeconds(com.taskqueue.models.TaskType type) {
        boolean isTest = "true".equals(System.getProperty("is.test"));
        if (isTest) {
            return switch (type) {
                case EMAIL -> new int[] { 1, 2 };
                case PAYMENT -> new int[] { 2, 3 };
                case REPORT -> new int[] { 3, 5 };
                case NOTIFICATION -> new int[] { 1, 2 };
                case DATA_SYNC -> new int[] { 2, 4 };
            };
        } else {
            return switch (type) {
                case EMAIL -> new int[] { 15, 25 };
                case PAYMENT -> new int[] { 20, 30 };
                case REPORT -> new int[] { 30, 50 };
                case NOTIFICATION -> new int[] { 15, 25 };
                case DATA_SYNC -> new int[] { 20, 40 };
            };
        }
    }
}
