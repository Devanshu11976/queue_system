package com.taskqueue.models;

/**
 * Task lifecycle status states.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
