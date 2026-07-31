package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.observability.AgentObservabilityProperties;
import com.zzp.aiagent.observability.AgentTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import com.zzp.aiagent.observability.AgentObservationNames;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 McpToolInvoker 的工具调用、错误处理和客户端解析逻辑。
 *
 * <h3>测试分类</h3>
 * 单元测试（mock McpSyncClient），不发起真实 MCP 连接。
 *
 * <h3>关键验证点</h3>
 * - 成功调用 → 返回文本内容
 * - 工具返回错误 → 抛出 BusinessException
 * - 返回空内容 → 抛出 BusinessException
 * - 客户端解析逻辑
 */
@DisplayName("McpToolInvoker")
@ExtendWith(MockitoExtension.class)
@Tag("unit")
class McpToolInvokerTest {

    @Mock
    private McpSyncClient mcpClient;

    private ObjectMapper objectMapper;
    private McpToolInvoker invoker;
    private TestObservationRegistry observations;
    private SimpleMeterRegistry meters;
    private AgentTelemetry telemetry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        observations = TestObservationRegistry.create();
        meters = new SimpleMeterRegistry();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        telemetry = spy(new AgentTelemetry(observations, meters,
                beans.getBeanProvider(io.micrometer.tracing.Tracer.class),
                new AgentObservabilityProperties(true, false)));
        invoker = new McpToolInvoker(List.of(mcpClient), objectMapper, telemetry);
    }

    // ── 成功调用 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("callTool")
    class CallTool {

        @Test
        @DisplayName("成功 → 返回文本内容")
        void returnsTextContent() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("image-retrieval-server", "1.0.0"));
            McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("{\"photos\":[{\"id\":1,\"alt\":\"test\"}]}")),
                    false);
            when(mcpClient.callTool(any())).thenReturn(result);

            String response = invoker.callTool("pexelsSearchPhotos",
                    Map.of("query", "test", "perPage", 5, "page", 1));

            assertThat(response).contains("\"photos\"");
            assertThat(response).contains("\"id\":1");
            observations.assertThat().hasObservationWithNameEqualTo(AgentObservationNames.MCP_CALL);
            assertThat(meters.get("agent.mcp.calls").counter().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("SSE 不传播上下文时 → 携带合法 traceparent 供服务端创建 Span Link")
        void carriesTraceparentOnlyAsSpanLinkMetadata() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("image-retrieval-server", "1.0.0"));
            when(mcpClient.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("{\"photos\":[]}")), false));
            String traceparent =
                    "00-11111111111111111111111111111111-2222222222222222-01";
            doReturn(traceparent).when(telemetry).currentTraceParent();

            invoker.callTool("pexelsSearchPhotos",
                    Map.of("query", "test", "perPage", 3, "page", 1));

            ArgumentCaptor<McpSchema.CallToolRequest> request =
                    ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
            verify(mcpClient).callTool(request.capture());
            assertThat(request.getValue().arguments())
                    .containsEntry(McpToolInvoker.LINK_TRACEPARENT_ARGUMENT, traceparent);
        }

        @Test
        @DisplayName("工具返回 isError=true → 抛出 BusinessException")
        void throwsOnToolError() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("image-retrieval-server", "1.0.0"));
            McpSchema.CallToolResult errorResult = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Pexels API rate limit exceeded")),
                    true);
            when(mcpClient.callTool(any())).thenReturn(errorResult);

            assertThatThrownBy(() -> invoker.callTool("pexelsSearchPhotos",
                    Map.of("query", "test", "perPage", 5, "page", 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("MCP 工具返回错误")
                    .hasMessageContaining("rate limit");
        }

        @Test
        @DisplayName("返回空内容 → 抛出 BusinessException")
        void throwsOnEmptyContent() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("image-retrieval-server", "1.0.0"));
            McpSchema.CallToolResult emptyResult = new McpSchema.CallToolResult(
                    List.of(), false);
            when(mcpClient.callTool(any())).thenReturn(emptyResult);

            assertThatThrownBy(() -> invoker.callTool("pexelsSearchPhotos",
                    Map.of("query", "test", "perPage", 5, "page", 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("MCP 工具返回空内容");
        }

        @Test
        @DisplayName("网络异常 → 抛出 BusinessException")
        void throwsOnNetworkError() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("image-retrieval-server", "1.0.0"));
            when(mcpClient.callTool(any())).thenThrow(new RuntimeException("Connection refused"));

            assertThatThrownBy(() -> invoker.callTool("pexelsSearchPhotos",
                    Map.of("query", "test", "perPage", 5, "page", 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("MCP 工具调用失败")
                    .hasMessageContaining("Connection refused");
            observations.assertThat().hasObservationWithNameEqualTo(AgentObservationNames.MCP_CALL);
            assertThat(meters.get("agent.mcp.calls").counter().count()).isEqualTo(1);
        }
    }

    // ── 客户端解析 ───────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveClient")
    class ResolveClient {

        @Test
        @DisplayName("按服务器名称匹配 → 返回匹配的客户端")
        void matchesByServerName() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("image-retrieval-server", "1.0.0"));

            McpSyncClient resolved = invoker.resolveClient();
            assertThat(resolved).isSameAs(mcpClient);
        }

        @Test
        @DisplayName("名称不匹配但有唯一已初始化客户端 → 兜底返回")
        void fallbackToOnlyInitializedClient() {
            when(mcpClient.isInitialized()).thenReturn(true);
            when(mcpClient.getServerInfo()).thenReturn(
                    new McpSchema.Implementation("some-other-server", "1.0.0"));

            McpSyncClient resolved = invoker.resolveClient();
            assertThat(resolved).isSameAs(mcpClient);
        }
    }

    // ── 提取文本内容 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("extractTextContent")
    class ExtractTextContent {

        @Test
        @DisplayName("有 TextContent → 返回文本")
        void extractsText() {
            McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("hello world")),
                    false);
            assertThat(invoker.extractTextContent(result)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("无内容 → 返回 null")
        void nullOnEmpty() {
            McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                    List.of(), false);
            assertThat(invoker.extractTextContent(result)).isNull();
        }

        @Test
        @DisplayName("content 为 null → 返回 null")
        void nullOnNullContent() {
            McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                    (List<McpSchema.Content>) null, false);
            assertThat(invoker.extractTextContent(result)).isNull();
        }
    }
}
