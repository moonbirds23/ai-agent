package com.zzp.aiagent.domain.rag;

import java.time.LocalDateTime;
import java.util.List;

public record RagTrace(
        String chatId,
        Long userId,
        String originalQuery,
        String rewrittenQuery,
        Object criteria,
        Object candidates,
        Object selected,
        String templateCode,
        String enhancedPrompt,
        long latencyMs,
        LocalDateTime createTime
) {
    public static RagTrace of(String chatId, String originalQuery, String enhancedPrompt, long latencyMs) {
        return new RagTrace(chatId, null, originalQuery, null, null, null, null, null,
                enhancedPrompt, latencyMs, LocalDateTime.now());
    }
}
