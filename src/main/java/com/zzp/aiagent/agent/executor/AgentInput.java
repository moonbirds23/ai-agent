package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.task.TaskPlan;
import org.springframework.ai.content.Media;

import java.util.Map;

public record AgentInput(
        String userText,
        Media userMedia,
        Map<String, Object> toolContext,
        String chatId,
        TaskPlan plan
) {
    public static AgentInput of(String userText, Media userMedia,
                                 Map<String, Object> toolContext, String chatId, TaskPlan plan) {
        return new AgentInput(userText, userMedia, toolContext, chatId, plan);
    }
}
