package com.zzp.aiagent.agent.task;

/**
 * A deterministic step in the backend task plan.
 *
 * @param code        stable step code for frontend/test assertions
 * @param description human-readable step summary
 * @param required    whether failing this step blocks delivery
 * @param toolName    expected tool name, nullable for model-only steps
 */
public record TaskStep(
        String code,
        String description,
        boolean required,
        String toolName
) {
    public static TaskStep of(String code, String description, boolean required, String toolName) {
        return new TaskStep(code, description, required, toolName);
    }
}
