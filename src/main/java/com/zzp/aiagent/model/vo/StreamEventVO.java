package com.zzp.aiagent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEventVO(
        String type,
        String chatId,
        String content,
        ChatResponseVO data
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
}
