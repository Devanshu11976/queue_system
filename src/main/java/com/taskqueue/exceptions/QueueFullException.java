package com.taskqueue.exceptions;

/**
 * Thrown when the task queue is at capacity and cannot accept new tasks.
 */
public class QueueFullException extends TaskQueueException {
    public QueueFullException(int capacity) {
        super("Task queue is full. Maximum capacity of " + capacity + " reached.");
    }
}
