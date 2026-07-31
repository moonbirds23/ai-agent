# MCP SSE W3C Trace Context 最小 Spike

## 结论

当前项目使用的 Spring AI `1.0.0`、MCP Java SDK `0.10.0` 和 SSE
传输无法在每次 MCP `tools/call` HTTP POST 中可靠传播活动 W3C Trace
Context。

因此本阶段不能把以下调用画成同一 traceId 下的严格父子链：

```text
主应用 agent.mcp.call -> MCP Server -> Pexels HTTP
```

当前实现如实降级为：

```text
Trace 1: 主应用 agent.mcp.call
                    \
                     \ Span Link
                      \
Trace 2: MCP Server Span -> Pexels HTTP Span
```

主应用和服务端 Span 均记录：

```text
agent.mcp.trace_context_propagated=false
```

服务端还记录：

```text
agent.mcp.span_link.created=true|false
```

## 源码审计证据

MCP SDK `HttpClientSseClientTransport` 在构造传输时创建并复用一个
`HttpRequest.Builder`。每次 `sendMessage` 仅设置 URI 和 POST body，然后
直接调用 JDK `HttpClient.sendAsync`。

该发送路径：

- 不读取当前 OpenTelemetry / Micrometer Context；
- 不调用 W3C `TextMapPropagator.inject`；
- 没有每次发送前的动态请求头回调；
- Spring AI 的 SSE Client 自动配置也没有为每次请求注入追踪上下文。

传输 Builder 的 `customizeRequest` 只能在连接创建阶段修改共享 Builder，
不能正确表达每次工具调用各自不同的活动 Span。

## 网络级实验

测试：

```text
src/test/java/com/zzp/aiagent/integration/mcp/McpSseTraceContextSpikeTest.java
```

实验步骤：

1. 启动本地 HTTP SSE 端点，并捕获 MCP 消息 POST 的实际请求头。
2. 创建两个 traceId 不同的活动父 Span。
3. 在每个 Span Scope 内，用项目依赖的 W3C Propagator 手动注入 Map，
   证明测试环境能生成包含当前 traceId 的合法 `traceparent`。
4. 在相同 Scope 内通过 MCP SDK 真实发送两次 JSON-RPC `tools/call`。
5. 检查服务端实际收到的 `traceparent` 与 `tracestate`。

实际结果：

```text
活动父 Span:                  2
对照载体中的合法 traceparent: 2
MCP POST traceparent:         0
MCP POST tracestate:          0
```

执行命令：

```powershell
$env:JAVA_HOME='D:\develop\java\JDK\jdk-21'
mvn -Dtest=McpSseTraceContextSpikeTest test
```

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。

## Span Link 降级验证

服务端验证测试：

```text
mcp-servers/image-retrieval-server/src/test/java/com/zzp/imageretrievalmcp/observability/McpServerTelemetryTest.java
```

已验证：

- MCP Server Span 是新 Trace 的根 Span；
- Server traceId 与被链接的主应用 traceId 不相同；
- Server Span Link 指向主应用 MCP Client Span 的真实 traceId + spanId；
- Pexels HTTP Span 与 MCP Server Span 同 traceId；
- Pexels HTTP Span 的 parentSpanId 等于 MCP Server Span 的 spanId；
- `agent.mcp.trace_context_propagated=false`；
- `agent.mcp.span_link.created=true`。

主应用测试还验证了 W3C `traceparent` 作为 Link 元数据随 MCP
工具参数发送。`toolCallId`、`turnId`、日志字段和自定义业务 ID 均未用于
冒充父子关系。

## 当前限制与后续可选改进

这是当前 Spring AI 1.0.0 + MCP SDK 0.10.0 SSE 发送路径的限制。
Jaeger 中会显示两条 Trace；需要通过 Server Span 上的 Span Link 查看
跨 Trace 因果关系，不能描述为“端到端同一 Trace”。

后续可选改进：

1. 升级到明确支持逐请求 Trace Context 注入的 Spring AI / MCP SDK；
2. 替换为支持标准 HTTP 中间件传播的传输方式，例如经验证后的
   Streamable HTTP；
3. 实现自定义 MCP Client Transport，在每次 POST 前从当前 Context
   动态调用 W3C Propagator 注入请求头。

任何改进都必须重新运行网络级 Spike；只有服务端实际收到并提取了
`traceparent`，才能切换为同 traceId 父子链并将
`agent.mcp.trace_context_propagated` 设为 `true`。
