package com.zzp.aiagent.agent.task;

import java.util.List;

/**
 * Serializable task state emitted to the frontend and available for debugging.
 */
public record TaskStatusSnapshot(
        String turnId,
        TaskType taskType,
        TaskLifecycleStatus status,
        String userGoal,
        List<TaskStep> steps,
        VerificationResult verification,
        List<ToolExecutionRecord> evidence
) {
}
