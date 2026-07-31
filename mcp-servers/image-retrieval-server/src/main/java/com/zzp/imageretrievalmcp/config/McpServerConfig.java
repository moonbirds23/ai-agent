package com.zzp.imageretrievalmcp.config;

import com.zzp.imageretrievalmcp.tool.HealthCheckTool;
import com.zzp.imageretrievalmcp.tool.PexelsTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal MCP server configuration placeholder.
 * Auto-configuration via spring-ai-starter-mcp-server-webmvc handles
 * the bulk of MCP server setup based on application.yml properties.
 */
@Configuration
public class McpServerConfig {

    @Bean
    ToolCallbackProvider imageRetrievalToolCallbackProvider(
            PexelsTools pexelsTools,
            HealthCheckTool healthCheckTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(pexelsTools, healthCheckTool)
                .build();
    }
}
