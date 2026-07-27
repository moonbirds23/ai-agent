package com.zzp.aiagent.agent.task;

/**
 * A task plan together with the decisions made while producing it.
 */
public record PlanningResult(
        TaskPlan plan,
        PlanSource source,
        boolean validationFailed,
        boolean repairAttempted
) {
}
