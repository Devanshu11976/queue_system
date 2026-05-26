package com.taskqueue.services;

import com.taskqueue.models.Task;
import com.taskqueue.models.TaskStatus;
import com.taskqueue.models.TaskType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

public class TaskExecutorTest {

    @Test
    public void testSimulationDurationSeconds() {
        int[] emailSec = TaskExecutor.getSimulationDurationSeconds(TaskType.EMAIL);
        assertEquals(1, emailSec[0]);
        assertEquals(2, emailSec[1]);

        int[] paymentSec = TaskExecutor.getSimulationDurationSeconds(TaskType.PAYMENT);
        assertEquals(2, paymentSec[0]);
        assertEquals(3, paymentSec[1]);

        int[] reportSec = TaskExecutor.getSimulationDurationSeconds(TaskType.REPORT);
        assertEquals(3, reportSec[0]);
        assertEquals(5, reportSec[1]);
    }

    @Test
    public void testSuccessfulExecution() {
        TaskExecutor executor = new TaskExecutor();
        Task task = new Task("Normal Task", TaskType.EMAIL, "success-payload", Instant.now().plusSeconds(60), 5);

        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertNull(task.getStartedAt());
        assertNull(task.getCompletedAt());

        executor.execute(task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertNotNull(task.getStartedAt());
        assertNotNull(task.getCompletedAt());
        assertEquals(0, task.getRetryCount());
        assertNull(task.getFailureReason());
    }

    @Test
    public void testExecutionFailureAndRetryScheduling() throws InterruptedException {
        TaskExecutor executor = new TaskExecutor();
        Task task = new Task("Failure Task", TaskType.EMAIL, "error occurred", Instant.now().plusSeconds(60), 5);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Task> requeuedTaskRef = new AtomicReference<>();

        executor.setRequeueCallback(t -> {
            requeuedTaskRef.set(t);
            latch.countDown();
        });

        // This task will sleep ~1s, then throw TaskExecutionException.
        // It will increment retryCount from 0 to 1.
        // Since retryCount (1) < maxRetries (3), it schedules a retry with a delay of 2^1 = 2 seconds.
        long startTime = System.currentTimeMillis();
        executor.execute(task);
        long duration = System.currentTimeMillis() - startTime;

        // Verify task is put back in PENDING status while waiting for retry
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals(1, task.getRetryCount());
        assertEquals("Simulated failure from payload trigger.", task.getFailureReason());

        // Wait for the scheduler to trigger the requeue callback (should take 2 seconds of delay + some buffers)
        // We give it a generous 4-second timeout to avoid test flakiness on slower environments.
        boolean callbackTriggered = latch.await(4, TimeUnit.SECONDS);

        assertTrue(callbackTriggered, "Requeue callback should have been triggered within 4 seconds");
        assertNotNull(requeuedTaskRef.get());
        assertEquals(task.getTaskId(), requeuedTaskRef.get().getTaskId());
    }

    @Test
    public void testExecutionFailureMaxRetriesReached() {
        TaskExecutor executor = new TaskExecutor();
        Task task = new Task("Definitive Failure Task", TaskType.EMAIL, "error occurred", Instant.now().plusSeconds(60), 5);

        // Simulate that the task has already failed twice (retryCount = 2)
        task.incrementRetryCount();
        task.incrementRetryCount();
        assertEquals(2, task.getRetryCount());

        // Undergoing next failure will increment retryCount to 3.
        // Since retryCount (3) is equal to maxRetries (3), it should fail definitively.
        executor.execute(task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals(3, task.getRetryCount());
        assertEquals("Simulated failure from payload trigger.", task.getFailureReason());
        assertNotNull(task.getCompletedAt());
    }
}
