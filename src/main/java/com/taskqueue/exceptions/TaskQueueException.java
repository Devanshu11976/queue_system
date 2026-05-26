package com.taskqueue.exceptions;

/**
 * Base exception for the Task Queue System.
 * All custom domain exceptions extend this class.
 */
public class TaskQueueException extends Exception {
    public TaskQueueException(String message) {
        super(message);
    }

    public TaskQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
