package com.zzp.aiagent.model.dto.memory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL chat_message 表的 DTO。
 */
public record ChatMessageRecord(
        Long id,
        String conversationId,
        String role,
        String content,
        List<ImageRef> imageRefs,
        Map<String, Object> metadata,
        LocalDateTime createdAt
) {
    public ChatMessageRecord(String conversationId, String role, String content,
                             List<ImageRef> imageRefs, Map<String, Object> metadata) {
        this(null, conversationId, role, content, imageRefs, metadata, null);
    }

    public ChatMessageRecord(String conversationId, String role, String content) {
        this(null, conversationId, role, content, null, null, null);
    }

    public MessageRecord toMessageRecord() {
        return new MessageRecord(role, content, imageRefs);
    }
}
