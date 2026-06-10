package com.zzp.aiagent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEventVO(
        String type,
        String chatId,
        String content,
        Object data
) {
    public static StreamEventVO chatId(String chatId) {
        return new StreamEventVO("chatId", chatId, null, null);
    }

    public static StreamEventVO token(String content) {
        return new StreamEventVO("token", null, content, null);
    }

    public static StreamEventVO done(String chatId, ChatResponseVO data) {
        return new StreamEventVO("done", chatId, null, data);
    }

    public static StreamEventVO error(String message) {
        return new StreamEventVO("error", null, message, null);
    }

    public static StreamEventVO progress(String chatId, String content) {
        return new StreamEventVO("progress", chatId, content, null);
    }

    public static StreamEventVO imageCandidates(String chatId, ImageCandidatesEventVO data) {
        return new StreamEventVO("image_candidates", chatId, null, data);
    }

    public static StreamEventVO imageGenerated(String chatId, ImageGeneratedEventVO data) {
        return new StreamEventVO("image_generated", chatId, null, data);
    }

    public static StreamEventVO taskPlanned(String chatId, Object data) {
        return new StreamEventVO("task_planned", chatId, null, data);
    }

    public static StreamEventVO taskVerified(String chatId, Object data) {
        return new StreamEventVO("task_verified", chatId, null, data);
    }

    /** Emitted when the model invokes a tool — shows the thinking process to the user. */
    public static StreamEventVO toolCall(String toolName, String label, String chatId) {
        return new StreamEventVO("tool_call", chatId, label, null);
    }
}
