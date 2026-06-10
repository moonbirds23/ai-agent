package com.zzp.aiagent.agent.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PromptBudgetManager {

    private final PromptProperties props;

    public PromptBudgetManager(PromptProperties props) {
        this.props = props;
    }

    public String trim(String text, int maxChars) {
        if (text == null || text.isBlank()) return "";
        if (text.length() <= maxChars) return text;
        String trimmed = text.substring(0, maxChars - 3);
        int lastDot = trimmed.lastIndexOf('.');
        int lastNewline = trimmed.lastIndexOf('\n');
        int breakPoint = Math.max(lastDot, lastNewline);
        if (breakPoint > maxChars / 2) {
            trimmed = trimmed.substring(0, breakPoint + 1);
        }
        return trimmed + "...";
    }

    public String trimSystem(String systemPrompt) { return trim(systemPrompt, props.maxSystemChars()); }
    public String trimTask(String taskPrompt) { return trim(taskPrompt, props.maxTaskChars()); }
    public String trimMemory(String memoryContext) { return trim(memoryContext, props.maxMemoryChars()); }
    public String trimRag(String ragContext) { return trim(ragContext, props.maxRagChars()); }
}
