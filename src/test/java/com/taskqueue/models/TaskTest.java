package com.taskqueue.models;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

public class TaskTest {

    @Test
    public void testTaskConstructorAndGetters() {
        String name = "Test Task";
        TaskType type = TaskType.EMAIL;
        String payload = "test@example.com";
        Instant deadline = Instant.now().plusSeconds(60);
        int priority = 5;

        Task task = new Task(name, type, payload, deadline, priority);

        assertNotNull(task.getTaskId());
        assertEquals(name, task.getName());
        assertEquals(type, task.getType());
        assertEquals(payload, task.getPayload());
        assertEquals(deadline, task.getDeadline());
        assertEquals(priority, task.getPriority());
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals(0, task.getRetryCount());
        assertNull(task.getFailureReason());
        assertNull(task.getAiDecision());
    }

    @Test
    public void testPriorityClamping() {
        Task task = new Task("Test", TaskType.EMAIL, "data", Instant.now().plusSeconds(60), 5);

        // Lower bound clamp
        task.setPriority(0);
        assertEquals(1, task.getPriority());
        task.setPriority(-5);
        assertEquals(1, task.getPriority());

        // Upper bound clamp
        task.setPriority(11);
        assertEquals(10, task.getPriority());
        task.setPriority(100);
        assertEquals(10, task.getPriority());

        // Valid ranges
        task.setPriority(7);
        assertEquals(7, task.getPriority());
    }

    @Test
    public void testCompareToDifferentPriorities() {
        Instant deadline = Instant.now().plusSeconds(60);
        Task highPriority = new Task("High", TaskType.EMAIL, "1", deadline, 8);
        Task lowPriority = new Task("Low", TaskType.EMAIL, "2", deadline, 4);

        // Since compareTo is written for high-to-low priority queuing:
        // highPriority.compareTo(lowPriority) should yield < 0 (highPriority goes first)
        // lowPriority.compareTo(highPriority) should yield > 0
        assertTrue(highPriority.compareTo(lowPriority) < 0);
        assertTrue(lowPriority.compareTo(highPriority) > 0);
    }

    @Test
    public void testCompareToSamePriorityFifoFallback() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        
        Task first = new Task("First", TaskType.EMAIL, "1", deadline, 5);
        // Sleep briefly to guarantee different creation times
        Thread.sleep(10);
        Task second = new Task("Second", TaskType.EMAIL, "2", deadline, 5);

        // Since they have the same priority, creation time determines order.
        // first.compareTo(second) should yield < 0 (first goes first)
        // second.compareTo(first) should yield > 0
        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(first) > 0);
    }

    @Test
    public void testStateMutations() {
        Task task = new Task("Test", TaskType.EMAIL, "data", Instant.now().plusSeconds(60), 5);

        task.setStatus(TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, task.getStatus());

        Instant now = Instant.now();
        task.setStartedAt(now);
        assertEquals(now, task.getStartedAt());

        task.setCompletedAt(now);
        assertEquals(now, task.getCompletedAt());

        task.incrementRetryCount();
        assertEquals(1, task.getRetryCount());

        task.setFailureReason("error info");
        assertEquals("error info", task.getFailureReason());
    }
}
