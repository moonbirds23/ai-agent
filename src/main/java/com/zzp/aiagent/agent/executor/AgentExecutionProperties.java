package com.zzp.aiagent.agent.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.agent")
public record AgentExecutionProperties(String executionMode) {

    public AgentExecutionProperties {
        if (executionMode == null || executionMode.isBlank()) {
            executionMode = "hybrid";
        }
    }
}
