package com.zzp.aiagent.memory;

import com.zzp.aiagent.manager.RedisChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
public class MemorySummaryService {

    private final RedisChatMemory chatMemory;

    public MemorySummaryService(RedisChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public String buildSummary(String conversationId, int lookbackMessages) {
        List<Message> history = chatMemory.get(conversationId, lookbackMessages);
        if (history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            String text = msg.getText();
            if (text == null || text.isBlank()) continue;
            if (text.contains("[ID:") || text.contains("参考图") || text.contains("图库图片")) {
                String snippet = text.length() > 120 ? text.substring(0, 120) + "..." : text;
                sb.append(snippet).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
