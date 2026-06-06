package com.zzp.aiagent.agent.task;

import java.util.Map;

/**
 * Structured record of a single tool invocation.
 * <p>
 * Unlike the tool's text return value (which is for the LLM to read),
 * this record is the authoritative evidence of what the tool actually did.
 */
public record ToolExecutionRecord(
        String turnId,
        String toolName,
        Map<String, Object> input,
        Map<String, Object> output,
        boolean success,
        String errorMessage,
        String sideEffect,
        long startedAt,
        long finishedAt
) {

    // ── well-known side effects ─────────────────────────────────────

    public static final String NONE = "NONE";
    public static final String IMAGE_GENERATED = "IMAGE_GENERATED";
    public static final String IMAGE_CANDIDATES_RETURNED = "IMAGE_CANDIDATES_RETURNED";
    public static final String GALLERY_CREATED = "GALLERY_CREATED";
    public static final String GALLERY_UPDATED = "GALLERY_UPDATED";
    public static final String GALLERY_FAVORITED = "GALLERY_FAVORITED";
    public static final String GALLERY_DELETED = "GALLERY_DELETED";
    public static final String WEB_FETCHED = "WEB_FETCHED";

    // ── factory methods ─────────────────────────────────────────────

    public static ToolExecutionRecord success(String turnId, String toolName,
                                               Map<String, Object> input,
                                               Map<String, Object> output,
                                               String sideEffect) {
        long now = System.currentTimeMillis();
        return new ToolExecutionRecord(turnId, toolName, input, output,
                true, null, sideEffect != null ? sideEffect : NONE, now, now);
    }

    public static ToolExecutionRecord failure(String turnId, String toolName,
                                               Map<String, Object> input,
                                               String errorMessage) {
        long now = System.currentTimeMillis();
        return new ToolExecutionRecord(turnId, toolName, input, Map.of(),
                false, errorMessage, NONE, now, now);
    }

    /** Elapsed time in milliseconds. */
    public long elapsedMs() {
        return finishedAt - startedAt;
    }
}
