package com.zzp.aiagent.agent.task;

import java.util.List;
import java.util.Map;

/**
 * A deterministic step in the backend task plan.
 *
 * @param code        stable step code for frontend/test assertions
 * @param description human-readable step summary
 * @param required    whether failing this step blocks delivery
 * @param toolName    expected tool name, nullable for model-only steps
 * @param dependsOn   step codes that must complete before this one
 * @param input       pre-filled tool input (nullable for LLM to decide)
 * @param status      current step execution status
 */
public record TaskStep(
        String code,
        String description,
        boolean required,
        String toolName,
        List<String> dependsOn,
        Map<String, Object> input,
        StepStatus status
) {
    public static TaskStep of(String code, String description, boolean required, String toolName) {
        return new TaskStep(code, description, required, toolName, List.of(), Map.of(), StepStatus.PENDING);
    }

    public static TaskStep of(String code, String description, boolean required, String toolName,
                               List<String> dependsOn) {
        return new TaskStep(code, description, required, toolName, dependsOn, Map.of(), StepStatus.PENDING);
    }

    public TaskStep withStatus(StepStatus newStatus) {
        return new TaskStep(code, description, required, toolName, dependsOn, input, newStatus);
    }
}
