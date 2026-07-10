package com.zzp.aiagent.integration.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the image-retrieval MCP integration.
 *
 * <p>Controls whether image retrieval calls go through the local Pexels HTTP client
 * or are routed through the MCP server for a service-oriented architecture.</p>
 */
@ConfigurationProperties(prefix = "app.integrations.image-retrieval")
public record McpIntegrationProperties(String mode) {

    public McpIntegrationProperties {
        if (mode == null || mode.isBlank()) {
            mode = "local";
        }
    }

    /**
     * @return true if the MCP mode is activated (IMAGE_RETRIEVAL_MODE=mcp)
     */
    public boolean isMcpMode() {
        return "mcp".equalsIgnoreCase(mode);
    }
}
