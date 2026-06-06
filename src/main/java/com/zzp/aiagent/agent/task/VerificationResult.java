package com.zzp.aiagent.agent.task;

import java.util.Map;

/**
 * Result of task verification — the authoritative judgment on whether
 * a user request was actually fulfilled.
 *
 * @param status      overall task outcome
 * @param deliverable whether the user-visible promise was kept
 * @param userMessage human-readable summary (safe to show to the user)
 * @param data        structured details for programmatic consumers
 */
public record VerificationResult(
        TaskStatus status,
        boolean deliverable,
        String userMessage,
        Map<String, Object> data
) {

    // ── factory methods ─────────────────────────────────────────────

    public static VerificationResult success(String message, Map<String, Object> data) {
        return new VerificationResult(TaskStatus.SUCCESS, true, message, data);
    }

    public static VerificationResult failed(String message) {
        return new VerificationResult(TaskStatus.FAILED, false, message, Map.of());
    }

    public static VerificationResult partialSuccess(String message, Map<String, Object> data) {
        return new VerificationResult(TaskStatus.PARTIAL_SUCCESS, true, message, data);
    }

    public static VerificationResult needMoreInfo(String message) {
        return new VerificationResult(TaskStatus.NEED_MORE_INFO, false, message, Map.of());
    }

    public static VerificationResult maxStepsExceeded(int maxSteps) {
        return new VerificationResult(TaskStatus.MAX_STEPS_EXCEEDED, false,
                "任务执行步数超限（最大 " + maxSteps + " 步），请简化需求", Map.of("maxSteps", maxSteps));
    }
}
