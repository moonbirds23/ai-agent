package com.zzp.aiagent.memory;

import com.zzp.aiagent.manager.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
public class MemoryContextBuilder {

    private final RedisChatMemory chatMemory;
    private final MemorySanitizer sanitizer;
    private final int promptWindowMessages;
    private final int maxContextChars;

    public MemoryContextBuilder(RedisChatMemory chatMemory,
                                MemorySanitizer sanitizer,
                                @Value("${app.chat-memory.prompt-window-messages:8}") int promptWindowMessages,
                                @Value("${app.chat-memory.max-context-chars:3000}") int maxContextChars) {
        this.chatMemory = chatMemory;
        this.sanitizer = sanitizer;
        this.promptWindowMessages = promptWindowMessages;
        this.maxContextChars = maxContextChars;
    }

    public List<Message> build(String conversationId) {
        List<Message> raw = chatMemory.get(conversationId, promptWindowMessages * 2);
        List<Message> clean = new ArrayList<>();
        int totalChars = 0;

        for (Message msg : raw) {
            String text = msg.getText();
            if (text == null || text.isBlank()) continue;

            if (text.startsWith("【用户从图库中选择了以下参考图片】")
                    || text.startsWith("【系统参考上下文】")
                    || text.startsWith("【rag")) {
                continue;
            }

            if (sanitizer.isPseudoToolCall(text) || sanitizer.hasFakeImages(text)) {
                continue;
            }

            int charCount = text.length();
            if (totalChars + charCount > maxContextChars) {
                break;
            }

            clean.add(msg);
            totalChars += charCount;
        }

        log.debug("[MemoryContextBuilder] built {} clean messages ({} chars) for conv={}",
                clean.size(), totalChars, conversationId);
        return clean;
    }
}
