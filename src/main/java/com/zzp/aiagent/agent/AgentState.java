package com.zzp.aiagent.agent;

/**
 * Agent lifecycle states.
 */
public enum AgentState {
    /** Ready to accept a new task. */
    IDLE,
    /** Actively executing — thinking or calling tools. */
    RUNNING,
    /** Completed normally. */
    FINISHED,
    /** Terminated due to error or step limit. */
    ERROR
}
