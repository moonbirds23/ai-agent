package com.zzp.aiagent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.chat-memory")
public record ChatMemoryProperties(
        int maxMessages,
        int promptWindowMessages,
        int maxContextChars,
        boolean enableSemanticSummary,
        int ttlDays,
        int maxConversationMessages
) {
    public ChatMemoryProperties {
        if (maxMessages <= 0) maxMessages = 50;
        if (promptWindowMessages <= 0) promptWindowMessages = 8;
        if (maxContextChars <= 0) maxContextChars = 3000;
        if (ttlDays <= 0) ttlDays = 7;
        if (maxConversationMessages <= 0) maxConversationMessages = 200;
    }
}
