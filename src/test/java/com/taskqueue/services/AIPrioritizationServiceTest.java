package com.taskqueue.services;

import com.taskqueue.models.AIDecisionLog;
import com.taskqueue.models.Task;
import com.taskqueue.models.TaskType;
import com.taskqueue.utils.ApiConfig;
import com.taskqueue.utils.QueueProperties;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AIPrioritizationServiceTest {

    private AIPrioritizationService service;
    private HttpClient mockHttpClient;

    @BeforeEach
    public void setUp() throws Exception {
        service = new AIPrioritizationService();
        mockHttpClient = mock(HttpClient.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        ApiConfig.clearRuntimeApiKey();
        ApiConfig.setForceNoKey(false);
    }

    private void injectMockHttpClient() throws Exception {
        Field field = AIPrioritizationService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, mockHttpClient);
    }

    @Test
    public void testDeterministicFallbackWhenApiKeyNotConfigured() throws Exception {
        ApiConfig.setForceNoKey(true);
        try {
            Task task = new Task("Test Fallback", TaskType.PAYMENT, "payload", Instant.now().plusSeconds(60), 5);

            // Execute prioritization
            service.prioritizeTask(task, 0, 0, 0.0);

            // Check fallback priority
            int expectedFallback = QueueProperties.get().getFallbackPriority(TaskType.PAYMENT);
            assertEquals(expectedFallback, task.getPriority());

            AIDecisionLog decision = task.getAiDecision();
            assertNotNull(decision);
            assertEquals("FALLBACK", decision.getRecommendation());
            assertEquals(expectedFallback, decision.getSuggestedPriority());
            assertEquals(5, decision.getOriginalPriority());
            assertTrue(decision.getReasoning().contains("Groq AI unavailable"));
            assertTrue(decision.getWarnings().get(0).contains("API key not configured"));
        } finally {
            ApiConfig.setForceNoKey(false);
        }
    }

    @Test
    public void testSuccessfulAiPrioritization() throws Exception {
        injectMockHttpClient();
        ApiConfig.setRuntimeApiKey("gsk-groq-test-key-12345");
        assertTrue(ApiConfig.isApiKeyConfigured());

        Task task = new Task("Test Success", TaskType.EMAIL, "payload", Instant.now().plusSeconds(60), 5);

        // Prepare mocked response from Groq Chat Completions API
        JSONObject responseJson = new JSONObject();
        
        JSONObject customDecision = new JSONObject();
        customDecision.put("suggestedPriority", 7);
        customDecision.put("reasoning", "Low priority email batch but needs standard execution.");
        customDecision.put("estimatedWaitTime", 12);
        customDecision.put("queuePosition", 3);
        customDecision.put("warnings", new JSONArray().put("Simulated warning"));
        customDecision.put("recommendation", "NORMAL");

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", customDecision.toString());

        JSONObject choice = new JSONObject();
        choice.put("message", message);
        responseJson.put("choices", new JSONArray().put(choice));

        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 350);
        usage.put("completion_tokens", 80);
        responseJson.put("usage", usage);

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson.toString());

        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenReturn((HttpResponse) mockResponse);

        // Execute prioritization
        service.prioritizeTask(task, 4, 2, 5.0);

        // Check target updates
        assertEquals(7, task.getPriority());
        AIDecisionLog decision = task.getAiDecision();
        assertNotNull(decision);
        assertEquals("NORMAL", decision.getRecommendation());
        assertEquals(7, decision.getSuggestedPriority());
        assertEquals("Low priority email batch but needs standard execution.", decision.getReasoning());
        assertEquals(12, decision.getEstimatedWaitTime());
        assertEquals(3, decision.getQueuePosition());
        assertEquals(1, decision.getWarnings().size());
        assertEquals("Simulated warning", decision.getWarnings().get(0));

        // Verify headers set in request
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();

        assertEquals("https://api.groq.com/openai/v1/chat/completions", request.uri().toString());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
        assertEquals("Bearer gsk-groq-test-key-12345", request.headers().firstValue("Authorization").orElse(null));
    }

    @Test
    public void testUrgentRecommendationOverride() throws Exception {
        injectMockHttpClient();
        ApiConfig.setRuntimeApiKey("gsk-groq-test-key-12345");

        Task task = new Task("Test Urgent", TaskType.PAYMENT, "critical", Instant.now().plusSeconds(60), 5);

        JSONObject responseJson = new JSONObject();
        JSONObject customDecision = new JSONObject();
        customDecision.put("suggestedPriority", 8); // Normal suggestion is 8
        customDecision.put("reasoning", "Urgent request detected");
        customDecision.put("estimatedWaitTime", 2);
        customDecision.put("queuePosition", 1);
        customDecision.put("warnings", new JSONArray());
        customDecision.put("recommendation", "URGENT"); // Recommend URGENT

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", customDecision.toString());
        JSONObject choice = new JSONObject();
        choice.put("message", message);
        responseJson.put("choices", new JSONArray().put(choice));

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson.toString());

        when(mockHttpClient.send(any(), any())).thenReturn((HttpResponse) mockResponse);

        service.prioritizeTask(task, 0, 0, 0.0);

        // URGENT overrides suggestedPriority to 10
        assertEquals(10, task.getPriority());
        assertEquals("URGENT", task.getAiDecision().getRecommendation());
    }

    @Test
    public void testDeferRecommendationOverride() throws Exception {
        injectMockHttpClient();
        ApiConfig.setRuntimeApiKey("gsk-groq-test-key-12345");

        Task task = new Task("Test Defer", TaskType.DATA_SYNC, "non-urgent data", Instant.now().plusSeconds(60), 5);

        JSONObject responseJson = new JSONObject();
        JSONObject customDecision = new JSONObject();
        customDecision.put("suggestedPriority", 3);
        customDecision.put("reasoning", "Background process deferral suggested");
        customDecision.put("estimatedWaitTime", 300);
        customDecision.put("queuePosition", 20);
        customDecision.put("warnings", new JSONArray());
        customDecision.put("recommendation", "DEFER"); // Recommend DEFER

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", customDecision.toString());
        JSONObject choice = new JSONObject();
        choice.put("message", message);
        responseJson.put("choices", new JSONArray().put(choice));

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson.toString());

        when(mockHttpClient.send(any(), any())).thenReturn((HttpResponse) mockResponse);

        service.prioritizeTask(task, 0, 0, 0.0);

        // DEFER overrides suggestedPriority to 1
        assertEquals(1, task.getPriority());
        assertEquals("DEFER", task.getAiDecision().getRecommendation());
    }

    @Test
    public void testCachingPreventsDuplicateCalls() throws Exception {
        injectMockHttpClient();
        ApiConfig.setRuntimeApiKey("gsk-groq-test-key-12345");

        // Submit first task
        Task task1 = new Task("T1", TaskType.PAYMENT, "payload", Instant.now().plusSeconds(60), 5);

        JSONObject responseJson = new JSONObject();
        JSONObject customDecision = new JSONObject();
        customDecision.put("suggestedPriority", 9);
        customDecision.put("reasoning", "Cached Payment decision test.");
        customDecision.put("estimatedWaitTime", 4);
        customDecision.put("queuePosition", 1);
        customDecision.put("warnings", new JSONArray());
        customDecision.put("recommendation", "NORMAL");

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", customDecision.toString());
        JSONObject choice = new JSONObject();
        choice.put("message", message);
        responseJson.put("choices", new JSONArray().put(choice));

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(responseJson.toString());

        when(mockHttpClient.send(any(), any())).thenReturn((HttpResponse) mockResponse);

        // 1st Prioritization (Should hit mock API)
        service.prioritizeTask(task1, 2, 1, 3.0);
        assertEquals(9, task1.getPriority());

        // Submit second task with identical cache properties: TaskType (PAYMENT), queueSize bucket (2/5 = 0), activeThreads (1)
        Task task2 = new Task("T2", TaskType.PAYMENT, "payload", Instant.now().plusSeconds(60), 5);

        // 2nd Prioritization (Should hit Cache)
        service.prioritizeTask(task2, 3, 1, 3.0); // queueSize bucket: 3/5 = 0. Matches key!

        assertEquals(9, task2.getPriority());
        assertEquals("Cached Payment decision test.", task2.getAiDecision().getReasoning());

        // Verify send was called only ONCE
        verify(mockHttpClient, times(1)).send(any(), any());
    }

    @Test
    public void testApiExceptionTriggersFallback() throws Exception {
        injectMockHttpClient();
        ApiConfig.setRuntimeApiKey("gsk-groq-test-key-12345");

        Task taskException = new Task("T Exception", TaskType.REPORT, "payload", Instant.now().plusSeconds(60), 5);

        // Simulating Network Exception
        when(mockHttpClient.send(any(), any())).thenThrow(new IOException("Simulated Connection Timeout"));

        // Executes prioritization (should catch exception and invoke fallback)
        service.prioritizeTask(taskException, 0, 0, 0.0);

        int expectedFallback = QueueProperties.get().getFallbackPriority(TaskType.REPORT);
        assertEquals(expectedFallback, taskException.getPriority());
        assertEquals("FALLBACK", taskException.getAiDecision().getRecommendation());
        assertTrue(taskException.getAiDecision().getWarnings().get(0).contains("Connection Timeout"));
    }

    @Test
    public void testApiNon200StatusTriggersFallback() throws Exception {
        injectMockHttpClient();
        ApiConfig.setRuntimeApiKey("gsk-groq-test-key-12345");

        Task task500 = new Task("T 500", TaskType.REPORT, "payload", Instant.now().plusSeconds(60), 5);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");
        when(mockHttpClient.send(any(), any())).thenReturn((HttpResponse) mockResponse);

        service.prioritizeTask(task500, 0, 0, 0.0);

        int expectedFallback = QueueProperties.get().getFallbackPriority(TaskType.REPORT);
        assertEquals(expectedFallback, task500.getPriority());
        assertEquals("FALLBACK", task500.getAiDecision().getRecommendation());
        assertTrue(task500.getAiDecision().getWarnings().get(0).contains("500"));
    }
}
