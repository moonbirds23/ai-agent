package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Low-level invoker that sends tool calls to the image-retrieval MCP server
 * and returns the raw JSON text response.
 *
 * <p>Spring AI's {@code McpClientAutoConfiguration} creates a
 * {@code List<McpSyncClient>} bean, one per configured connection.
 * This invoker finds the client whose server name matches {@code image-retrieval}
 * and uses it for all tool calls.</p>
 */
@Component
@Profile("!test")
@Slf4j
public class McpToolInvoker {

    /**
     * Connection name that must match the server info name exposed by the
     * MCP server (configured via {@code spring.ai.mcp.server.name}).
     */
    static final String SERVER_NAME = "image-retrieval-server";

    private final List<McpSyncClient> mcpClients;
    private final ObjectMapper objectMapper;

    public McpToolInvoker(List<McpSyncClient> mcpClients, ObjectMapper objectMapper) {
        this.mcpClients = mcpClients;
        this.objectMapper = objectMapper;
    }

    /**
     * Call a tool on the MCP server and return the response as a JSON string.
     *
     * @param toolName  canonical tool name (e.g. "pexelsSearchPhotos")
     * @param arguments tool arguments as string-keyed map
     * @return text content of the first content entry
     * @throws BusinessException if the tool call fails or returns an error
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = resolveClient();
        log.debug("[McpToolInvoker] Calling tool '{}' with args: {}", toolName, arguments);

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);
        McpSchema.CallToolResult result;

        try {
            result = client.callTool(request);
        } catch (Exception e) {
            log.error("[McpToolInvoker] Tool call failed tool={}", toolName, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "MCP 工具调用失败：" + toolName + " — " + e.getMessage());
        }

        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = extractTextContent(result);
            log.error("[McpToolInvoker] Tool returned error: tool={}, error={}", toolName, errorText);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "MCP 工具返回错误：" + toolName + " — " + errorText);
        }

        String text = extractTextContent(result);
        if (text == null || text.isBlank()) {
            log.warn("[McpToolInvoker] Tool returned empty content: tool={}", toolName);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "MCP 工具返回空内容：" + toolName);
        }

        log.debug("[McpToolInvoker] Tool call succeeded: tool={}, responseLength={}", toolName, text.length());
        return text;
    }

    /**
     * Find the McpSyncClient whose server info name matches {@link #CLIENT_NAME}.
     */
    McpSyncClient resolveClient() {
        for (McpSyncClient client : mcpClients) {
            try {
                if (client.isInitialized()) {
                    McpSchema.Implementation serverInfo = client.getServerInfo();
                    if (serverInfo != null && SERVER_NAME.equals(serverInfo.name())) {
                        return client;
                    }
                }
            } catch (Exception e) {
                log.debug("[McpToolInvoker] Skipping uninitialized/failed client: {}", e.getMessage());
            }
        }

        // Fallback: if exactly one client is available and initialized, use it
        List<McpSyncClient> initializedClients = mcpClients.stream()
                .filter(c -> {
                    try { return c.isInitialized(); } catch (Exception e) { return false; }
                })
                .toList();

        if (initializedClients.size() == 1) {
            McpSyncClient fallback = initializedClients.get(0);
            log.info("[McpToolInvoker] Using fallback client (only one initialized)");
            return fallback;
        }

        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                "未找到可用的 MCP 客户端（image-retrieval-server）。已初始化客户端数量：" + initializedClients.size()
                + "，总客户端数量：" + mcpClients.size());
    }

    /**
     * Extract the text from the first {@code TextContent} entry in the result.
     */
    String extractTextContent(McpSchema.CallToolResult result) {
        List<McpSchema.Content> contentList = result.content();
        if (contentList == null || contentList.isEmpty()) {
            return null;
        }
        for (McpSchema.Content content : contentList) {
            if (content instanceof McpSchema.TextContent tc) {
                return tc.text();
            }
        }
        return null;
    }
}
