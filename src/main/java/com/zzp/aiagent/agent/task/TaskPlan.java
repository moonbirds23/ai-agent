package com.zzp.aiagent.agent.task;

import java.util.List;
import java.util.Map;

/**
 * Backend-owned plan for one user turn.
 * <p>
 * The plan is intentionally deterministic: it captures what the system must
 * verify later, while Spring AI still decides the exact tool-calling sequence.
 */
public record TaskPlan(
        String turnId,
        TaskType taskType,
        String userGoal,
        List<TaskStep> steps,
        boolean requiresImage,
        boolean requiresGeneration,
        boolean requiresExternalSearch,
        Map<String, Object> slots
) {
    public TaskPlan {
        if (slots == null) slots = Map.of();
    }

    public static TaskPlan chat(String turnId, String userGoal) {
        return new TaskPlan(turnId, TaskType.CHAT, userGoal,
                List.of(TaskStep.of("respond", "生成对话回复", true, null)),
                false, false, false, Map.of());
    }
}
