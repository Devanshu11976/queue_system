package com.taskqueue.exceptions;

/**
 * Thrown when an error occurs during task execution.
 */
public class TaskExecutionException extends TaskQueueException {
    public TaskExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public TaskExecutionException(String message) {
        super(message);
    }
}
