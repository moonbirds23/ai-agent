package com.zzp.aiagent.agent.task;

/**
 * Outcome of a single task after verification.
 */
public enum TaskStatus {

    /** All deliverable conditions met. */
    SUCCESS,

    /** The required tool(s) were called but failed. */
    FAILED,

    /** Some deliverables met, some failed (e.g. image generated but save failed). */
    PARTIAL_SUCCESS,

    /** Not enough information to proceed — need user input. */
    NEED_MORE_INFO,

    /** Task was rejected (e.g. content safety). */
    REJECTED,

    /** Execution timed out. */
    TIMEOUT,

    /** Tool-call count exceeded the per-turn limit. */
    MAX_STEPS_EXCEEDED
}
