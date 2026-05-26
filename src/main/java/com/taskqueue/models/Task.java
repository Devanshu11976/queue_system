package com.taskqueue.models;

import java.time.Instant;
import java.util.UUID;

/**
 * Thread-safe domain model representing a queue Task.
 * Implements Comparable for high-to-low priority queuing with FIFO fallback.
 */
public class Task implements Comparable<Task> {
    private final UUID taskId;
    private final String name;
    private final TaskType type;
    private final String payload;
    private final Instant createdAt;
    private final Instant deadline;
    private final int maxRetries;

    private volatile int priority;
    private volatile TaskStatus status;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile int retryCount;
    private volatile String failureReason;
    private volatile AIDecisionLog aiDecision;

    public Task(String name, TaskType type, String payload, Instant deadline, int priority) {
        this.taskId = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.deadline = deadline;
        this.priority = priority;
        this.status = TaskStatus.PENDING;
        this.maxRetries = com.taskqueue.utils.QueueProperties.get().getMaxRetries();
        this.retryCount = 0;
    }

    // Getters - volatile variables can be read without locking
    public UUID getTaskId() { return taskId; }
    public String getName() { return name; }
    public TaskType getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeadline() { return deadline; }
    public int getMaxRetries() { return maxRetries; }

    public int getPriority() { return priority; }
    public TaskStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getRetryCount() { return retryCount; }
    public String getFailureReason() { return failureReason; }
    public AIDecisionLog getAiDecision() { return aiDecision; }

    // Synchronized state mutations
    public synchronized void setPriority(int priority) {
        if (priority < 1) this.priority = 1;
        else if (priority > 10) this.priority = 10;
        else this.priority = priority;
    }

    public synchronized void setStatus(TaskStatus status) {
        this.status = status;
    }

    public synchronized void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public synchronized void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public synchronized void incrementRetryCount() {
        this.retryCount++;
    }

    public synchronized void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public synchronized void setAiDecision(AIDecisionLog aiDecision) {
        this.aiDecision = aiDecision;
    }

    /**
     * Implements queue order sorting:
     * 1. Higher priority value (e.g. 10) runs BEFORE lower priority value (e.g. 1).
     * 2. FIFO order (earlier createdAt runs first) when priorities are equal.
     */
    @Override
    public int compareTo(Task other) {
        // Compare priorities in descending order (higher priority first)
        int priorityComparison = Integer.compare(other.priority, this.priority);
        if (priorityComparison != 0) {
            return priorityComparison;
        }
        // Fallback to FIFO based on creation time (earlier createdAt first)
        return this.createdAt.compareTo(other.createdAt);
    }

    @Override
    public String toString() {
        return "Task{" +
                "id=" + taskId +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", priority=" + priority +
                ", status=" + status +
                ", retries=" + retryCount +
                '}';
    }
}
