package com.zzp.aiagent.memory.model;

import java.time.LocalDateTime;
import java.util.Map;

public record MemoryEntry(
        String conversationId,
        String turnId,
        MemoryEntryType type,
        String role,
        String content,
        Map<String, Object> metadata,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
    public static MemoryEntry of(String conversationId, String turnId, MemoryEntryType type,
                                  String role, String content, Map<String, Object> metadata) {
        return new MemoryEntry(conversationId, turnId, type, role, content,
                metadata, LocalDateTime.now(), null);
    }

    public boolean injectable() {
        return switch (type) {
            case USER_INTENT, ASSISTANT_FINAL_RESPONSE, USER_PREFERENCE, RESOURCE_REFERENCE -> true;
            default -> false;
        };
    }
}
