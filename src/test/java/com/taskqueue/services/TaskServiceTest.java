package com.taskqueue.services;

import com.taskqueue.exceptions.InvalidTaskException;
import com.taskqueue.exceptions.TaskNotFoundException;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskStatus;
import com.taskqueue.models.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TaskServiceTest {

    private TaskService service;
    private AIPrioritizationService mockAiService;
    private TaskExecutor mockExecutor;

    @BeforeEach
    public void setUp() {
        mockAiService = mock(AIPrioritizationService.class);
        mockExecutor = mock(TaskExecutor.class);
        // Initialize coordinator with 2 threads and capacity of 10
        service = new TaskService(2, 10, mockAiService, mockExecutor);
    }

    @AfterEach
    public void tearDown() {
        service.shutdown();
    }

    @Test
    public void testCreateTaskSuccessfully() throws Exception {
        String name = "Process Invoice";
        TaskType type = TaskType.PAYMENT;
        String payload = "amount=450";
        Instant deadline = Instant.now().plusSeconds(100);
        int basePriority = 5;

        Task task = service.createTask(name, type, payload, deadline, basePriority);

        assertNotNull(task);
        assertEquals(name, task.getName());
        assertEquals(type, task.getType());
        assertEquals(payload, task.getPayload());
        assertEquals(deadline, task.getDeadline());
        assertEquals(TaskStatus.PENDING, task.getStatus());

        // Verify it was stored in memory database
        Task retrieved = service.getTask(task.getTaskId());
        assertSame(task, retrieved);

        // Verify AI prioritization was called during creation
        verify(mockAiService, times(1)).prioritizeTask(eq(task), anyInt(), anyInt(), anyDouble());
        
        // Verify it was put into the queue
        assertEquals(1, service.getThreadPool().getQueueSize());
    }

    @Test
    public void testCreateTaskValidationFails() {
        Instant validDeadline = Instant.now().plusSeconds(100);

        // Blank name
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("", TaskType.EMAIL, "data", validDeadline, 5)
        );

        // Null name
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask(null, TaskType.EMAIL, "data", validDeadline, 5)
        );

        // Null type
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("Name", null, "data", validDeadline, 5)
        );

        // Null payload
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("Name", TaskType.EMAIL, null, validDeadline, 5)
        );

        // Past deadline
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("Name", TaskType.EMAIL, "data", Instant.now().minusSeconds(10), 5)
        );

        // Too-short deadline (min deadline default is 10 seconds)
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("Name", TaskType.EMAIL, "data", Instant.now().plusSeconds(2), 5)
        );

        // Out-of-bounds priority (<1)
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("Name", TaskType.EMAIL, "data", validDeadline, 0)
        );

        // Out-of-bounds priority (>10)
        assertThrows(InvalidTaskException.class, () -> 
            service.createTask("Name", TaskType.EMAIL, "data", validDeadline, 11)
        );
    }

    @Test
    public void testTaskNotFoundException() {
        assertThrows(TaskNotFoundException.class, () -> service.getTask(UUID.randomUUID()));
    }

    @Test
    public void testCancelPendingTask() throws Exception {
        Task task = service.createTask("T", TaskType.EMAIL, "data", Instant.now().plusSeconds(100), 5);
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals(1, service.getThreadPool().getQueueSize());

        service.cancelTask(task.getTaskId());

        // Cancelled task should update status and get removed from priority queue
        assertEquals(TaskStatus.CANCELLED, task.getStatus());
        assertNotNull(task.getCompletedAt());
        assertEquals("Cancelled by user request.", task.getFailureReason());
        assertEquals(0, service.getThreadPool().getQueueSize());
    }

    @Test
    public void testGetTasksByStatusAndAllTasks() throws Exception {
        Task t1 = service.createTask("T1", TaskType.EMAIL, "data1", Instant.now().plusSeconds(100), 5);
        Task t2 = service.createTask("T2", TaskType.PAYMENT, "data2", Instant.now().plusSeconds(100), 5);

        Collection<Task> all = service.getAllTasks();
        assertEquals(2, all.size());

        List<Task> pending = service.getTasksByStatus(TaskStatus.PENDING);
        assertEquals(2, pending.size());

        List<Task> running = service.getTasksByStatus(TaskStatus.RUNNING);
        assertEquals(0, running.size());
    }

    @Test
    public void testStatsGeneration() throws Exception {
        Task t1 = service.createTask("T1", TaskType.EMAIL, "d1", Instant.now().plusSeconds(100), 5);
        Task t2 = service.createTask("T2", TaskType.PAYMENT, "d2", Instant.now().plusSeconds(100), 5);

        // Complete t1 manually to simulate stats
        t1.setStatus(TaskStatus.COMPLETED);
        t1.setStartedAt(Instant.now().minusSeconds(5));
        t1.setCompletedAt(Instant.now());
        service.getThreadPool().remove(t1);

        // Cancel t2 manually
        service.cancelTask(t2.getTaskId());

        Map<String, Object> stats = service.getStats();

        assertEquals(2L, stats.get("totalTasks"));
        assertEquals(0L, stats.get("pendingTasks"));
        assertEquals(1L, stats.get("completedTasks"));
        assertEquals(1L, stats.get("cancelledTasks"));
        assertEquals(0L, stats.get("failedTasks"));
        assertEquals(0, stats.get("queueSize"));
        assertEquals(2, stats.get("threadPoolSize"));
        assertEquals(10, stats.get("maxQueueCapacity"));

        // Completed task spent ~5 seconds
        double avg = (double) stats.get("avgCompletionTimeSeconds");
        assertTrue(avg >= 4.0 && avg <= 6.0);
    }
}
