package com.zzp.aiagent.agent.task;

public record RecoveryAction(
        RecoveryActionType type,
        String message,
        String nextTool
) {
    public static RecoveryAction none() {
        return new RecoveryAction(RecoveryActionType.NONE, "", null);
    }

    public static RecoveryAction askUser(String message) {
        return new RecoveryAction(RecoveryActionType.ASK_USER, message, null);
    }

    public static RecoveryAction fallback(String message, String nextTool) {
        return new RecoveryAction(RecoveryActionType.FALLBACK_TOOL, message, nextTool);
    }

    public static RecoveryAction retry(String message, String nextTool) {
        return new RecoveryAction(RecoveryActionType.RETRY_TOOL, message, nextTool);
    }

    public static RecoveryAction partial(String message) {
        return new RecoveryAction(RecoveryActionType.RETURN_PARTIAL, message, null);
    }
}
