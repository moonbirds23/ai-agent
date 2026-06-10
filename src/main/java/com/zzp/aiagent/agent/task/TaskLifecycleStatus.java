package com.zzp.aiagent.agent.task;

/**
 * Runtime lifecycle state for a planned task.
 * <p>
 * This is separate from {@link TaskStatus}, which represents the final
 * verification outcome.
 */
public enum TaskLifecycleStatus {
    PLANNED,
    RUNNING,
    VERIFYING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    NEED_MORE_INFO
}
