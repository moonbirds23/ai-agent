package com.zzp.aiagent.memory;

import com.zzp.aiagent.manager.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
public class MemoryContextBuilder {

    private final RedisChatMemory chatMemory;
    private final MemorySanitizer sanitizer;
    private final MemoryClassifier classifier;
    private final int promptWindowMessages;
    private final int maxContextChars;

    public MemoryContextBuilder(RedisChatMemory chatMemory,
                                MemorySanitizer sanitizer,
                                MemoryClassifier classifier,
                                ChatMemoryProperties properties) {
        this.chatMemory = chatMemory;
        this.sanitizer = sanitizer;
        this.classifier = classifier;
        this.promptWindowMessages = properties.promptWindowMessages();
        this.maxContextChars = properties.maxContextChars();
    }

    public List<Message> build(String conversationId) {
        List<Message> raw = chatMemory.get(conversationId, Math.max(promptWindowMessages * 3, 12));
        List<Message> clean = new ArrayList<>();
        int totalChars = 0;

        for (int i = raw.size() - 1; i >= 0 && clean.size() < promptWindowMessages; i--) {
            Message msg = raw.get(i);
            if (msg instanceof org.springframework.ai.chat.messages.SystemMessage) continue;
            String text = msg.getText();
            if (text == null || text.isBlank()) continue;

            if (sanitizer.isPseudoToolCall(text) || sanitizer.hasFakeImages(text)) {
                continue;
            }
            if (msg instanceof org.springframework.ai.chat.messages.UserMessage
                    && classifier.classifyUser(text)
                    != com.zzp.aiagent.memory.model.MemoryEntryType.USER_INTENT) continue;

            String sanitized = sanitizer.sanitize(text);
            if (sanitized == null || sanitized.isBlank()) continue;
            int charCount = sanitized.length();
            if (totalChars + charCount > maxContextChars) {
                continue;
            }

            clean.add(msg instanceof org.springframework.ai.chat.messages.UserMessage
                    ? new org.springframework.ai.chat.messages.UserMessage(sanitized)
                    : new org.springframework.ai.chat.messages.AssistantMessage(sanitized));
            totalChars += charCount;
        }
        Collections.reverse(clean);

        log.debug("[MemoryContextBuilder] built {} clean messages ({} chars) for conv={}",
                clean.size(), totalChars, conversationId);
        return List.copyOf(clean);
    }
}
