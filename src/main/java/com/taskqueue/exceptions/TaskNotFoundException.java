package com.taskqueue.exceptions;

/**
 * Thrown when a requested task cannot be found in the queue system.
 */
public class TaskNotFoundException extends TaskQueueException {
    public TaskNotFoundException(String taskId) {
        super("Task with ID " + taskId + " not found.");
    }
}
