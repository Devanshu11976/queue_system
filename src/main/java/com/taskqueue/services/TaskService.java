package com.taskqueue.services;

import com.taskqueue.exceptions.*;
import com.taskqueue.models.*;
import com.taskqueue.threadpool.ThreadPool;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Global coordinator managing the in-memory Task Database (ConcurrentHashMap),
 * Task validations, lifecycle controls, and statistics.
 */
public class TaskService {
    private final ConcurrentHashMap<UUID, Task> taskStore;
    private final ThreadPool threadPool;
    private final AIPrioritizationService aiService;
    private final TaskExecutor taskExecutor;
    private final Instant serverStartTime;

    public TaskService(int threadCount, int maxQueueCapacity, AIPrioritizationService aiService, TaskExecutor taskExecutor) {
        this.taskStore = new ConcurrentHashMap<>();
        this.aiService = aiService;
        this.taskExecutor = taskExecutor;
        this.threadPool = new ThreadPool(threadCount, maxQueueCapacity, taskExecutor);
        this.serverStartTime = Instant.now();

        // Bind the callback in taskExecutor to our requeueTask method
        this.taskExecutor.setRequeueCallback(this::requeueTask);
    }

    /**
     * Initializes and starts the ThreadPool worker threads.
     */
    public void start() {
        this.threadPool.start();
    }

    /**
     * Shuts down the thread pool and scheduler.
     */
    public void shutdown() {
        this.threadPool.shutdown();
    }

    /**
     * Submits a brand-new task to the system.
     * Integrates AI Prioritization before queuing.
     */
    public Task createTask(String name, TaskType type, String payload, Instant deadline, int basePriority) 
            throws InvalidTaskException, QueueFullException, ThreadPoolShutdownException {
        
        validateTaskParams(name, type, payload, deadline, basePriority);

        Task task = new Task(name, type, payload, deadline, basePriority);

        // Run AI prioritization inside a synchronized lock to capture a consistent queue state snapshot
        double completedRate = getRecentCompletionRate();
        int queueSize = threadPool.getQueueSize();
        int activeThreads = threadPool.getActiveThreadCount();

        aiService.prioritizeTask(task, queueSize, activeThreads, completedRate);

        try {
            // Enqueue prioritized task before making it visible in the store.
            threadPool.submit(task);
            taskStore.put(task.getTaskId(), task);
        } catch (QueueFullException | ThreadPoolShutdownException e) {
            task.setFailureReason(e.getMessage());
            throw e;
        }
        return task;
    }

    /**
     * Cancels an active or pending task.
     */
    public void cancelTask(UUID taskId) throws TaskNotFoundException {
        Task task = taskStore.get(taskId);
        if (task == null) {
            throw new TaskNotFoundException(taskId.toString());
        }

        synchronized (task) {
            if (task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.RUNNING) {
                task.setStatus(TaskStatus.CANCELLED);
                task.setCompletedAt(Instant.now());
                task.setFailureReason("Cancelled by user request.");

                // Attempt to remove from priority queue if still pending
                boolean removed = threadPool.remove(task);
                if (removed) {
                    System.out.println("[Task Service] Successfully removed pending task from queue: " + task.getName());
                } else {
                    System.out.println("[Task Service] Active task marked as CANCELLED (will complete or terminate): " + task.getName());
                }
            } else {
                System.out.println("[Task Service] Task cancellation ignored. Task already in terminal state: " + task.getStatus());
            }
        }
    }

    /**
     * Re-queues a task for execution (used for retries with backoff).
     */
    private void requeueTask(Task task) {
        synchronized (task) {
            if (task.getStatus() == TaskStatus.CANCELLED) {
                return;
            }
        }
        try {
            threadPool.submit(task);
        } catch (Exception e) {
            System.err.println("[Task Service] Failed to re-queue task '" + task.getName() + "': " + e.getMessage());
            task.setStatus(TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            task.setFailureReason("Failed to re-queue during backoff: " + e.getMessage());
        }
    }

    public Task getTask(UUID taskId) throws TaskNotFoundException {
        Task task = taskStore.get(taskId);
        if (task == null) {
            throw new TaskNotFoundException(taskId.toString());
        }
        return task;
    }

    public Collection<Task> getAllTasks() {
        return taskStore.values();
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskStore.values().stream()
                .filter(t -> t.getStatus() == status)
                .collect(Collectors.toList());
    }

    /**
     * Generates standard operational metrics and statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        long total = taskStore.size();
        long pending = getTasksCount(TaskStatus.PENDING);
        long running = getTasksCount(TaskStatus.RUNNING);
        long completed = getTasksCount(TaskStatus.COMPLETED);
        long failed = getTasksCount(TaskStatus.FAILED);
        long cancelled = getTasksCount(TaskStatus.CANCELLED);

        // Calculate average execution duration for completed tasks
        double avgCompletionTime = taskStore.values().stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED && t.getStartedAt() != null && t.getCompletedAt() != null)
                .mapToLong(t -> Duration.between(t.getStartedAt(), t.getCompletedAt()).toMillis())
                .average()
                .orElse(0.0) / 1000.0;

        // Group by type
        Map<String, Long> tasksByType = taskStore.values().stream()
                .collect(Collectors.groupingBy(t -> t.getType().name(), Collectors.counting()));

        // Active details from thread pool
        stats.put("totalTasks", total);
        stats.put("pendingTasks", pending);
        stats.put("runningTasks", running);
        stats.put("completedTasks", completed);
        stats.put("failedTasks", failed);
        stats.put("cancelledTasks", cancelled);
        stats.put("activeThreads", threadPool.getActiveThreadCount());
        stats.put("threadPoolSize", threadPool.getThreadCount());
        stats.put("maxQueueCapacity", threadPool.getMaxQueueCapacity());
        stats.put("queueSize", threadPool.getQueueSize());
        stats.put("avgCompletionTimeSeconds", Math.round(avgCompletionTime * 100.0) / 100.0);
        stats.put("tasksByType", tasksByType);
        stats.put("aiDecisionsMade", aiService.getAiDecisionsMade());
        stats.put("aiUrgentOverrides", aiService.getUrgentOverridesCount());

        List<Map<String, Object>> workersList = new ArrayList<>();
        for (com.taskqueue.threadpool.WorkerThread worker : threadPool.getWorkers()) {
            Map<String, Object> workerMap = new HashMap<>();
            workerMap.put("name", worker.getName());
            workerMap.put("isWorking", worker.isWorking());
            Task t = worker.getCurrentTask();
            if (t != null) {
                workerMap.put("taskName", t.getName());
                workerMap.put("taskId", t.getTaskId().toString());
                workerMap.put("taskType", t.getType().name());
                workerMap.put("taskPriority", t.getPriority());
                workerMap.put("startedAt", t.getStartedAt() != null ? t.getStartedAt().toEpochMilli() : 0);
            }
            workersList.add(workerMap);
        }
        stats.put("workers", workersList);

        return stats;
    }

    private long getTasksCount(TaskStatus status) {
        return taskStore.values().stream().filter(t -> t.getStatus() == status).count();
    }

    /**
     * Calculates completed tasks per minute in the last 10 minutes.
     */
    public double getRecentCompletionRate() {
        Instant tenMinsAgo = Instant.now().minus(Duration.ofMinutes(10));
        long completedCount = taskStore.values().stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED)
                .filter(t -> t.getCompletedAt() != null && t.getCompletedAt().isAfter(tenMinsAgo))
                .count();

        double elapsedMinutes = Duration.between(serverStartTime, Instant.now()).toMillis() / 60000.0;
        double partitionMinutes = Math.min(10.0, Math.max(0.5, elapsedMinutes));

        return completedCount / partitionMinutes;
    }

    private void validateTaskParams(String name, TaskType type, String payload, Instant deadline, int priority) 
            throws InvalidTaskException {
        if (name == null || name.trim().isEmpty()) {
            throw new InvalidTaskException("Task name cannot be empty.");
        }
        if (type == null) {
            throw new InvalidTaskException("Task type must be specified.");
        }
        if (payload == null) {
            throw new InvalidTaskException("Task payload cannot be null.");
        }
        if (deadline == null || deadline.isBefore(Instant.now())) {
            throw new InvalidTaskException("Task deadline must be a valid future timestamp.");
        }
        long minDeadlineSec = com.taskqueue.utils.QueueProperties.get().getDeadlineMinSeconds();
        long secondsUntil = Duration.between(Instant.now(), deadline).getSeconds();
        if (secondsUntil < minDeadlineSec) {
            throw new InvalidTaskException("Task deadline must be at least " + minDeadlineSec + " seconds in the future.");
        }
        int min = com.taskqueue.utils.QueueProperties.get().getPriorityMin();
        int max = com.taskqueue.utils.QueueProperties.get().getPriorityMax();
        if (priority < min || priority > max) {
            throw new InvalidTaskException("Task priority must be an integer between " + min + " and " + max + ".");
        }
    }

    public ThreadPool getThreadPool() {
        return threadPool;
    }

    public AIPrioritizationService getAiService() {
        return aiService;
    }
}
