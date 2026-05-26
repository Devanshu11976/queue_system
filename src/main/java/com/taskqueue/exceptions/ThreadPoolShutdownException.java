package com.taskqueue.exceptions;

/**
 * Thrown when trying to submit tasks to a thread pool that is already shut down.
 */
public class ThreadPoolShutdownException extends TaskQueueException {
    public ThreadPoolShutdownException() {
        super("Cannot submit task. The ThreadPool has been shut down.");
    }
}
