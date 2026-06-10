package com.zzp.aiagent.agent.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.prompt")
public record PromptProperties(
        int maxSystemChars,
        int maxTaskChars,
        int maxMemoryChars,
        int maxRagChars
) {
    public PromptProperties {
        if (maxSystemChars <= 0) maxSystemChars = 1500;
        if (maxTaskChars <= 0) maxTaskChars = 1200;
        if (maxMemoryChars <= 0) maxMemoryChars = 2500;
        if (maxRagChars <= 0) maxRagChars = 2500;
    }
}
