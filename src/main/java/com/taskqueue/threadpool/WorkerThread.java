package com.taskqueue.threadpool;

import com.taskqueue.models.Task;
import com.taskqueue.models.TaskStatus;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker thread that polls the ThreadPool's priority queue and executes tasks.
 */
public class WorkerThread extends Thread {
    private final ThreadPool pool;
    private final AtomicBoolean isWorking = new AtomicBoolean(false);
    private volatile Task currentTask = null;

    public WorkerThread(ThreadPool pool, int id) {
        super("WorkerThread-" + id);
        this.pool = pool;
    }

    @Override
    public void run() {
        System.out.println(getName() + " started and listening for tasks...");
        while (true) {
            try {
                if (pool.isShutdown() && pool.getQueueSize() == 0) {
                    break;
                }

                // Poll with a short timeout so shutdown can drain cleanly without interrupting work.
                Task task = pool.takeTask(250L);
                if (task == null) {
                    continue;
                }

                // Skip tasks removed from queue or already in a terminal/non-runnable state
                synchronized (task) {
                    if (task.getStatus() != TaskStatus.PENDING) {
                        continue;
                    }
                }

                currentTask = task;
                isWorking.set(true);

                // Execute the task via TaskExecutor
                pool.getTaskExecutor().execute(task);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                isWorking.set(false);
                currentTask = null;
            }
        }
        System.out.println(getName() + " terminated gracefully.");
    }

    public boolean isWorking() {
        return isWorking.get();
    }

    public Task getCurrentTask() {
        return currentTask;
    }
}
