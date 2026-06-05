package com.zzp.aiagent.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holder for the current turn's image base64 data.
 * <p>
 * Spring AI tool methods may execute on Reactor worker threads where Spring MVC
 * request scope is not active, so this context is explicitly keyed by a per-turn
 * id passed through {@link ToolContext} instead of using {@code @RequestScope}.
 */
@Component
@Profile("!test")
public class CurrentImageContext {

    public static final String CHAT_ID_CONTEXT_KEY = "chatId";
    public static final String TURN_ID_CONTEXT_KEY = "turnId";

    private final ConcurrentMap<String, String> imageBase64ByTurn = new ConcurrentHashMap<>();

    public void bind(String turnId, String imageBase64) {
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        if (imageBase64 == null || imageBase64.isBlank()) {
            imageBase64ByTurn.remove(turnId);
            return;
        }
        imageBase64ByTurn.put(turnId, imageBase64);
    }

    public String getImageBase64(ToolContext toolContext) {
        String turnId = contextValue(toolContext, TURN_ID_CONTEXT_KEY);
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        return imageBase64ByTurn.get(turnId);
    }

    public void clear(String turnId) {
        if (turnId != null && !turnId.isBlank()) {
            imageBase64ByTurn.remove(turnId);
        }
    }

    public void clearAll() {
        imageBase64ByTurn.clear();
    }

    private static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value instanceof String text ? text : null;
    }
}
