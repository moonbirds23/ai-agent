package com.zzp.aiagent.agent.task;

import java.util.List;
import java.util.Map;

/**
 * Serializable task state emitted to the frontend and available for debugging.
 */
public record TaskStatusSnapshot(
        String turnId,
        TaskType taskType,
        TaskLifecycleStatus status,
        String userGoal,
        List<TaskStep> steps,
        Map<String, StepStatus> stepStatuses,
        VerificationResult verification,
        RecoveryAction recoveryAction,
        List<ToolExecutionRecord> evidence
) {
}
