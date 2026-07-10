package com.zzp.imageretrievalmcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Capability-level health check tool.
 * Returns the MCP server status as a JSON string.
 */
@Component
public class HealthCheckTool {

    @Tool(description = "能力级健康检查，返回服务状态")
    public String healthCheck() {
        return "{\"status\":\"UP\",\"server\":\"image-retrieval-mcp-server\",\"version\":\"1.0.0\"}";
    }
}
