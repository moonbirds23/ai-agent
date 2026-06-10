package com.zzp.aiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
public class MemoryWriter {

    private final ChatMemory chatMemory;
    private final MemorySanitizer sanitizer;

    public MemoryWriter(ChatMemory chatMemory, MemorySanitizer sanitizer) {
        this.chatMemory = chatMemory;
        this.sanitizer = sanitizer;
    }

    public void writeUserIntent(String conversationId, String cleanUserText) {
        if (cleanUserText == null || cleanUserText.isBlank()) return;
        String sanitized = sanitizer.sanitize(cleanUserText);
        chatMemory.add(conversationId, List.of(new UserMessage(sanitized)));
        log.debug("[MemoryWriter] user intent written conv={}", conversationId);
    }

    public void writeAssistantResponse(String conversationId, String verifiedResponse) {
        if (verifiedResponse == null || verifiedResponse.isBlank()) return;
        String sanitized = sanitizer.sanitize(verifiedResponse);
        chatMemory.add(conversationId, List.of(new AssistantMessage(sanitized)));
        log.debug("[MemoryWriter] assistant response written conv={}", conversationId);
    }

    public void writeResourceSummary(String conversationId, String summary) {
        if (summary == null || summary.isBlank()) return;
        chatMemory.add(conversationId, List.of(new UserMessage("【资源摘要】" + summary)));
        log.debug("[MemoryWriter] resource summary written conv={}", conversationId);
    }
}
