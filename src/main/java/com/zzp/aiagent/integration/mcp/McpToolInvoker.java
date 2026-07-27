package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.observability.AgentObservationKeys;
import com.zzp.aiagent.observability.AgentObservationNames;
import com.zzp.aiagent.observability.AgentTelemetry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.UUID;

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
    private final AgentTelemetry telemetry;

    public McpToolInvoker(List<McpSyncClient> mcpClients, ObjectMapper objectMapper,
                          AgentTelemetry telemetry) {
        this.mcpClients = mcpClients;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
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
        long startedAt = System.nanoTime();
        String parentSpanId = telemetry.currentSpanId();
        String logicalToolCallId = parentSpanId != null && !parentSpanId.isBlank()
                ? parentSpanId
                : UUID.randomUUID().toString();
        AgentTelemetry.AgentObservation observation = telemetry.start(AgentObservationNames.MCP_CALL)
                .lowCardinality(AgentObservationKeys.Low.TOOL_NAME, toolName)
                .lowCardinality(AgentObservationKeys.Low.MCP_SERVER_NAME, SERVER_NAME)
                .lowCardinality(AgentObservationKeys.Low.MCP_TRANSPORT, "sse")
                .highCardinality(AgentObservationKeys.High.TOOL_CALL_ID, logicalToolCallId);
        try (var ignored = observation.openScope()) {
            McpSyncClient client = resolveClient();
            log.debug("[McpToolInvoker] Calling tool '{}' argumentCount={}",
                    toolName, arguments != null ? arguments.size() : 0);
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);
            McpSchema.CallToolResult result = client.callTool(request);
            if (Boolean.TRUE.equals(result.isError())) {
                String errorText = extractTextContent(result);
                log.error("[McpToolInvoker] Tool returned error: tool={}, errorLength={}",
                        toolName, errorText != null ? errorText.length() : 0);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "MCP 工具返回错误：" + toolName + " — " + errorText);
            }

            String text = extractTextContent(result);
            if (text == null || text.isBlank()) {
                log.warn("[McpToolInvoker] Tool returned empty content: tool={}", toolName);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "MCP 工具返回空内容：" + toolName);
            }

            observation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "success");
            recordMcpMetrics(toolName, "success", startedAt);
            log.debug("[McpToolInvoker] Tool call succeeded: tool={}, responseLength={}", toolName, text.length());
            return text;
        } catch (Exception error) {
            observation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error")
                    .error(new McpCallObservationError());
            recordMcpMetrics(toolName, "error", startedAt);
            log.error("[McpToolInvoker] Tool call failed tool={} errorType={}",
                    toolName, error.getClass().getName());
            if (error instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "MCP 工具调用失败：" + toolName + " — " + error.getMessage());
        } finally {
            observation.stop();
        }
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

    private void recordMcpMetrics(String toolName, String outcome, long startedAt) {
        telemetry.increment("agent.mcp.calls",
                AgentObservationKeys.Low.TOOL_NAME, toolName,
                AgentObservationKeys.Low.MCP_SERVER_NAME, SERVER_NAME,
                AgentObservationKeys.Low.OUTCOME, outcome);
        telemetry.record("agent.mcp.duration",
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)),
                AgentObservationKeys.Low.TOOL_NAME, toolName,
                AgentObservationKeys.Low.MCP_SERVER_NAME, SERVER_NAME,
                AgentObservationKeys.Low.OUTCOME, outcome);
    }

    private static final class McpCallObservationError extends RuntimeException {
        private McpCallObservationError() {
            super("MCP call failed; response content redacted");
        }
    }
}
