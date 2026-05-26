package com.taskqueue.threadpool;

import com.taskqueue.exceptions.QueueFullException;
import com.taskqueue.exceptions.ThreadPoolShutdownException;
import com.taskqueue.models.Task;
import com.taskqueue.services.TaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom thread pool that manages fixed worker threads using a priority queue.
 * Does not use any built-in ExecutorService classes.
 */
public class ThreadPool {
    private final int threadCount;
    private final int maxQueueCapacity;
    private final PriorityBlockingQueue<Task> queue;
    private final List<WorkerThread> workers;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final TaskExecutor taskExecutor;

    public ThreadPool(int threadCount, int maxQueueCapacity, TaskExecutor taskExecutor) {
        this.threadCount = threadCount;
        this.maxQueueCapacity = maxQueueCapacity;
        this.taskExecutor = taskExecutor;
        this.queue = new PriorityBlockingQueue<>();
        this.workers = new ArrayList<>(threadCount);
    }

    /**
     * Initializes and starts the worker threads.
     */
    public synchronized void start() {
        if (isShutdown.get()) {
            throw new IllegalStateException("ThreadPool is already shut down.");
        }
        if (!workers.isEmpty()) {
            return; // Already started
        }
        for (int i = 1; i <= threadCount; i++) {
            WorkerThread worker = new WorkerThread(this, i);
            workers.add(worker);
            worker.start();
        }
    }

    /**
     * Submits a task to the priority queue.
     * Enforces queue size limits and checks pool status thread-safely.
     */
    public void submit(Task task) throws QueueFullException, ThreadPoolShutdownException {
        if (isShutdown.get()) {
            throw new ThreadPoolShutdownException();
        }

        // Thread-safe capacity check
        synchronized (queue) {
            if (queue.size() >= maxQueueCapacity) {
                throw new QueueFullException(maxQueueCapacity);
            }
            queue.offer(task);
        }
    }

    /**
     * Blocks until a task is available for the worker threads to take.
     */
    public Task takeTask() throws InterruptedException {
        return queue.take();
    }

    /**
     * Polls for a task with a timeout so workers can drain gracefully during shutdown.
     */
    public Task takeTask(long timeoutMillis) throws InterruptedException {
        return queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Initiates a graceful shutdown of the thread pool.
     * Workers are allowed to finish currently executing tasks, but new submissions are rejected.
     */
    public synchronized void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            System.out.println("Shutting down ThreadPool...");
        }
    }

    // Getters and stats methods
    public boolean isShutdown() {
        return isShutdown.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    public List<Task> getQueuedTasks() {
        return new ArrayList<>(queue);
    }

    /**
     * Removes a task from the priority queue.
     */
    public boolean remove(Task task) {
        synchronized (queue) {
            return queue.remove(task);
        }
    }

    public TaskExecutor getTaskExecutor() {
        return taskExecutor;
    }

    /**
     * Counts how many worker threads are actively executing a task.
     */
    public int getActiveThreadCount() {
        int activeCount = 0;
        for (WorkerThread worker : workers) {
            if (worker.isWorking()) {
                activeCount++;
            }
        }
        return activeCount;
    }

    public List<WorkerThread> getWorkers() {
        return new ArrayList<>(workers);
    }

    public int getThreadCount() {
        return threadCount;
    }

    public int getMaxQueueCapacity() {
        return maxQueueCapacity;
    }
}
