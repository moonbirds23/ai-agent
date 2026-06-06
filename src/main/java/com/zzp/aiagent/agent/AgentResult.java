package com.zzp.aiagent.agent;

/**
 * Result produced by {@link Agent#run}.
 *
 * @param state       final agent state
 * @param content     the model's text response (may be null on error)
 * @param trace       point-in-time snapshot of the execution context
 */
public record AgentResult(
        AgentState state,
        String content,
        AgentContext.Snapshot trace
) {

    public boolean isSuccess() {
        return state == AgentState.FINISHED && content != null;
    }

    public boolean isError() {
        return state == AgentState.ERROR;
    }
}
