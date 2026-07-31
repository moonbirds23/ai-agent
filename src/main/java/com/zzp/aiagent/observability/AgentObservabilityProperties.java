package com.zzp.aiagent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.observability.agent")
public record AgentObservabilityProperties(
        boolean enabled,
        boolean pricingEnabled
) {
    public AgentObservabilityProperties {
        // Records keep explicit values; defaults are supplied by application.yml.
    }
}
