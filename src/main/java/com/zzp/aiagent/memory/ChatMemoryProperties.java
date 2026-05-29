package com.zzp.aiagent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.chat-memory")
public record ChatMemoryProperties(
        int maxMessages,
        int ttlDays
) {
    public ChatMemoryProperties {
        if (maxMessages <= 0) maxMessages = 50;
        if (ttlDays <= 0) ttlDays = 7;
    }
}
