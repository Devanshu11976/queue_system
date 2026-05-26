package com.taskqueue.threadpool;

import com.taskqueue.exceptions.QueueFullException;
import com.taskqueue.exceptions.ThreadPoolShutdownException;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskType;
import com.taskqueue.services.TaskExecutor;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ThreadPoolTest {

    @Test
    public void testThreadPoolProperties() {
        TaskExecutor mockExecutor = mock(TaskExecutor.class);
        ThreadPool pool = new ThreadPool(2, 5, mockExecutor);

        assertEquals(2, pool.getThreadCount());
        assertEquals(5, pool.getMaxQueueCapacity());
        assertSame(mockExecutor, pool.getTaskExecutor());
        assertFalse(pool.isShutdown());
        assertEquals(0, pool.getQueueSize());
        assertEquals(0, pool.getActiveThreadCount());
    }

    @Test
    public void testSubmitAndTakeTask() throws Exception {
        TaskExecutor mockExecutor = mock(TaskExecutor.class);
        ThreadPool pool = new ThreadPool(2, 5, mockExecutor);

        Task task1 = new Task("Task 1", TaskType.EMAIL, "data1", Instant.now().plusSeconds(60), 5);
        Task task2 = new Task("Task 2", TaskType.EMAIL, "data2", Instant.now().plusSeconds(60), 8);

        pool.submit(task1);
        pool.submit(task2);

        assertEquals(2, pool.getQueueSize());

        // PriorityBlockingQueue: task2 has priority 8, task1 has priority 5.
        // So takeTask should return task2 (highest priority) first!
        Task polled1 = pool.takeTask();
        assertEquals(task2.getTaskId(), polled1.getTaskId());

        Task polled2 = pool.takeTask();
        assertEquals(task1.getTaskId(), polled2.getTaskId());
    }

    @Test
    public void testQueueFullException() throws Exception {
        TaskExecutor mockExecutor = mock(TaskExecutor.class);
        ThreadPool pool = new ThreadPool(2, 2, mockExecutor);

        Task t1 = new Task("T1", TaskType.EMAIL, "1", Instant.now().plusSeconds(60), 5);
        Task t2 = new Task("T2", TaskType.EMAIL, "2", Instant.now().plusSeconds(60), 5);
        Task t3 = new Task("T3", TaskType.EMAIL, "3", Instant.now().plusSeconds(60), 5);

        pool.submit(t1);
        pool.submit(t2);

        assertThrows(QueueFullException.class, () -> pool.submit(t3));
    }

    @Test
    public void testThreadPoolShutdownAndException() throws Exception {
        TaskExecutor mockExecutor = mock(TaskExecutor.class);
        ThreadPool pool = new ThreadPool(2, 5, mockExecutor);

        pool.shutdown();
        assertTrue(pool.isShutdown());

        Task t = new Task("T", TaskType.EMAIL, "1", Instant.now().plusSeconds(60), 5);
        assertThrows(ThreadPoolShutdownException.class, () -> pool.submit(t));
    }

    @Test
    public void testShutdownDoesNotInterruptRunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        TaskExecutor slowExecutor = new TaskExecutor() {
            @Override
            public void execute(Task task) {
                task.setStartedAt(Instant.now());
                task.setStatus(com.taskqueue.models.TaskStatus.RUNNING);
                started.countDown();
                try {
                    Thread.sleep(300);
                    task.setCompletedAt(Instant.now());
                    task.setStatus(com.taskqueue.models.TaskStatus.COMPLETED);
                } catch (InterruptedException e) {
                    task.setCompletedAt(Instant.now());
                    task.setStatus(com.taskqueue.models.TaskStatus.FAILED);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
        };

        ThreadPool pool = new ThreadPool(1, 5, slowExecutor);
        pool.start();

        Task task = new Task("Shutdown Task", TaskType.EMAIL, "payload", Instant.now().plusSeconds(60), 5);
        pool.submit(task);

        assertTrue(started.await(2, TimeUnit.SECONDS));
        pool.shutdown();

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(com.taskqueue.models.TaskStatus.COMPLETED, task.getStatus());
    }

    @Test
    public void testWorkerExecution() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger executedCount = new AtomicInteger(0);

        // Subclass TaskExecutor to intercept call
        TaskExecutor spyExecutor = new TaskExecutor() {
            @Override
            public void execute(Task task) {
                executedCount.incrementAndGet();
                latch.countDown();
            }
        };

        ThreadPool pool = new ThreadPool(2, 5, spyExecutor);
        pool.start();

        Task t = new Task("Execute Task", TaskType.EMAIL, "payload", Instant.now().plusSeconds(60), 5);
        pool.submit(t);

        // Wait for workers to poll and execute the task
        boolean completed = latch.await(3, TimeUnit.SECONDS);
        
        assertTrue(completed, "Task should have been processed by worker threads within 3 seconds");
        assertEquals(1, executedCount.get());

        pool.shutdown();
    }

    @Test
    public void testWorkersExecuteTasksConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);

        TaskExecutor concurrentExecutor = new TaskExecutor() {
            @Override
            public void execute(Task task) {
                int running = concurrentExecutions.incrementAndGet();
                maxConcurrentExecutions.updateAndGet(current -> Math.max(current, running));
                bothStarted.countDown();
                try {
                    releaseWorkers.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrentExecutions.decrementAndGet();
                }
            }
        };

        ThreadPool pool = new ThreadPool(2, 5, concurrentExecutor);
        pool.start();

        Task first = new Task("Concurrent 1", TaskType.EMAIL, "payload-1", Instant.now().plusSeconds(60), 5);
        Task second = new Task("Concurrent 2", TaskType.PAYMENT, "payload-2", Instant.now().plusSeconds(60), 6);

        pool.submit(first);
        pool.submit(second);

        assertTrue(bothStarted.await(2, TimeUnit.SECONDS), "Both worker threads should start before release");
        assertTrue(maxConcurrentExecutions.get() >= 2, "At least two tasks should be executing concurrently");

        releaseWorkers.countDown();
        pool.shutdown();
    }
}
