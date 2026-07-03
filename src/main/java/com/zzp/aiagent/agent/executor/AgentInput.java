package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.task.TaskPlan;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;

public record AgentInput(
        String userText,
        String executionContext,
        List<Message> memoryMessages,
        Media userMedia,
        Map<String, Object> toolContext,
        String chatId,
        TaskPlan plan
) {
    public AgentInput {
        memoryMessages = memoryMessages != null ? List.copyOf(memoryMessages) : List.of();
        toolContext = toolContext != null ? Map.copyOf(toolContext) : Map.of();
    }

    public static AgentInput of(String userText, String executionContext, List<Message> memoryMessages,
                                 Media userMedia,
                                 Map<String, Object> toolContext, String chatId, TaskPlan plan) {
        return new AgentInput(userText, executionContext, memoryMessages,
                userMedia, toolContext, chatId, plan);
    }

    public String modelInput() {
        if (executionContext == null || executionContext.isBlank()) {
            return userText != null ? userText : "";
        }
        return executionContext + "\n\n【用户原始需求】\n" + (userText != null ? userText : "");
    }
}
