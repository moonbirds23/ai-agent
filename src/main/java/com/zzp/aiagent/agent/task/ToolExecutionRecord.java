package com.zzp.aiagent.agent.task;

import java.util.ArrayList;
import java.util.List;
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
        List<ResourceRef> resources,
        boolean success,
        String errorCode,
        String errorMessage,
        String sideEffect,
        boolean recoverable,
        String recoveryHint,
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
        return success(turnId, toolName, input, output, sideEffect, now, now);
    }

    public static ToolExecutionRecord success(String turnId, String toolName,
                                               Map<String, Object> input,
                                               Map<String, Object> output,
                                               String sideEffect,
                                               long startedAt,
                                               long finishedAt) {
        return new ToolExecutionRecord(turnId, toolName, input, output,
                inferResources(output), true, null, null,
                sideEffect != null ? sideEffect : NONE, false, null,
                startedAt, Math.max(startedAt, finishedAt));
    }

    public static ToolExecutionRecord failure(String turnId, String toolName,
                                               Map<String, Object> input,
                                               String errorMessage) {
        long now = System.currentTimeMillis();
        return failure(turnId, toolName, input, null, errorMessage, true, defaultRecoveryHint(toolName));
    }

    public static ToolExecutionRecord failure(String turnId, String toolName,
                                               Map<String, Object> input,
                                               String errorCode,
                                               String errorMessage,
                                               boolean recoverable,
                                               String recoveryHint) {
        long now = System.currentTimeMillis();
        return failure(turnId, toolName, input, errorCode, errorMessage,
                recoverable, recoveryHint, now, now);
    }

    public static ToolExecutionRecord failure(String turnId, String toolName,
                                               Map<String, Object> input,
                                               String errorCode,
                                               String errorMessage,
                                               boolean recoverable,
                                               String recoveryHint,
                                               long startedAt,
                                               long finishedAt) {
        return new ToolExecutionRecord(turnId, toolName, input, Map.of(), List.of(),
                false, errorCode, errorMessage, NONE, recoverable, recoveryHint,
                startedAt, Math.max(startedAt, finishedAt));
    }

    public ToolExecutionRecord withTiming(long startedAt, long finishedAt) {
        return new ToolExecutionRecord(turnId, toolName, input, output, resources,
                success, errorCode, errorMessage, sideEffect, recoverable, recoveryHint,
                startedAt, Math.max(startedAt, finishedAt));
    }

    /** Elapsed time in milliseconds. */
    public long elapsedMs() {
        return finishedAt - startedAt;
    }

    private static List<ResourceRef> inferResources(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        List<ResourceRef> refs = new ArrayList<>();
        Object imageUrl = output.get("imageUrl");
        if (imageUrl instanceof String s && !s.isBlank()) {
            refs.add(ResourceRef.image(s));
        }
        Object pictureId = output.get("pictureId");
        if (pictureId != null) {
            refs.add(ResourceRef.galleryPicture(pictureId, valueAsString(output.get("name"))));
        }
        Object pictureIds = output.get("pictureIds");
        if (pictureIds instanceof Iterable<?> ids) {
            for (Object id : ids) {
                refs.add(ResourceRef.galleryPicture(id, null));
            }
        }
        return List.copyOf(refs);
    }

    private static String valueAsString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String defaultRecoveryHint(String toolName) {
        if ("generateImage".equals(toolName)) {
            return "可以稍后重试，或简化生图描述后再生成";
        }
        if ("searchGallery".equals(toolName)) {
            return "可以更换关键词，或降级使用网络图片搜索";
        }
        if ("pexelsSearchPhotos".equals(toolName) || "imageSearch".equals(toolName)) {
            return "可以更换关键词，或降级搜索本地图库";
        }
        return "可以稍后重试或补充更明确的信息";
    }
}
