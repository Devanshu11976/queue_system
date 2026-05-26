package com.taskqueue.exceptions;

/**
 * Thrown when task parameters (type, payload, deadline) fail validation.
 */
public class InvalidTaskException extends TaskQueueException {
    public InvalidTaskException(String reason) {
        super("Invalid task definition: " + reason);
    }
}
