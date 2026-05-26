package com.taskqueue.models;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Model representing the AI Smart Prioritization analysis and output log.
 */
public class AIDecisionLog {
    private final UUID decisionId;
    private final UUID taskId;
    private final int originalPriority;
    private final int suggestedPriority;
    private final String reasoning;
    private final int estimatedWaitTime;
    private final int queuePosition;
    private final List<String> warnings;
    private final String recommendation; // URGENT, NORMAL, DEFER
    private final Instant timestamp;

    public AIDecisionLog(UUID taskId, int originalPriority, int suggestedPriority, 
                           String reasoning, int estimatedWaitTime, int queuePosition, 
                           List<String> warnings, String recommendation) {
        this.decisionId = UUID.randomUUID();
        this.taskId = taskId;
        this.originalPriority = originalPriority;
        this.suggestedPriority = suggestedPriority;
        this.reasoning = reasoning;
        this.estimatedWaitTime = estimatedWaitTime;
        this.queuePosition = queuePosition;
        this.warnings = warnings;
        this.recommendation = recommendation;
        this.timestamp = Instant.now();
    }

    public UUID getDecisionId() { return decisionId; }
    public UUID getTaskId() { return taskId; }
    public int getOriginalPriority() { return originalPriority; }
    public int getSuggestedPriority() { return suggestedPriority; }
    public String getReasoning() { return reasoning; }
    public int getEstimatedWaitTime() { return estimatedWaitTime; }
    public int getQueuePosition() { return queuePosition; }
    public List<String> getWarnings() { return warnings; }
    public String getRecommendation() { return recommendation; }
    public Instant getTimestamp() { return timestamp; }
}
