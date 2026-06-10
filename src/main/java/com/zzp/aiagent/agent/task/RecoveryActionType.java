package com.zzp.aiagent.agent.task;

public enum RecoveryActionType {
    NONE,
    RETRY_TOOL,
    FALLBACK_TOOL,
    ASK_USER,
    RETURN_PARTIAL,
    FAIL_FAST
}
