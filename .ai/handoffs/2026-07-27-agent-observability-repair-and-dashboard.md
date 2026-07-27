# Agent 可观测性修复与 Dashboard 验收

状态：done  
日期：2026-07-27

## 结果

Micrometer + OpenTelemetry/OTLP + Jaeger + Prometheus + Grafana 本地链路已落地并启动。

- 应用：8231
- MCP：8232
- Jaeger/OTLP：16686 / 4318
- Prometheus：9090
- Grafana：3000

Grafana Dashboard `AI Agent Observability` 已通过 API 与浏览器实际渲染验收，共 12 个面板。

## 本轮修复

1. MCP Server 注册 `pexelsSearchPhotos`、`pexelsCuratedPhotos`、`pexelsGetPhoto`、
   `healthCheck` 四个工具。
2. 生成/创意任务在真实 Chat 入口调用 `RagService`，形成 rewrite/retrieve/rerank/pack
   观测阶段；空上下文不产生 pack Span。
3. MCP 重试的物理调用复用父 Tool Span ID 作为逻辑 `toolCallId`。
4. 增加 Prometheus Registry 和 `/api/actuator/prometheus`。
5. 增加模型 Token、价格状态与估算成本指标；`glm-4-flash` 按官方免费价格配置。
6. 默认日志不再记录完整 Prompt、回复、Query Rewrite、搜索词、工具参数和图片 URL。
7. 修复 MCP String 工具结果的双层 JSON 解包。
8. 增加 Prometheus/Grafana 自动配置、官方包安装脚本、一键启动和自动冒烟脚本。

## 实测证据

| 场景 | Trace | Span |
|---|---|---:|
| RAG + 生图 + 隐私 Canary | `6b75f43a563489c89e85a92e25b884fc` | 24 |
| MCP Tool Calling | `0c29ab5a7e20d72d30a0068b4218eb47` | 18 |
| 流式 SSE / TTFT | `33093021b6da3880b6d89a1133bf5462` | 16 |
| 自动冒烟 Chat | `e0fe8afdfb752b2ff859fc53981d814a` | 12 |

RAG Trace 包含：

`agent.planner → agent.rag.rewrite → agent.rag.retrieve → agent.rag.rerank
→ agent.executor → tool_call generate-image → agent.verifier → agent.memory.write`

MCP Trace 包含：

`agent.planner → agent.executor → tool_call pexels-search-photos
→ agent.mcp.call → agent.verifier`

隐私 Canary `PRIVATE_CANARY_9F2C7A`：

- 应用/MCP 日志命中：0
- Jaeger Trace 命中：0
- 敏感 Trace 标签键命中：0

当前 Prometheus 实测样本：

- Agent turn：4
- TTFT：1
- MCP call：1
- RAG request：1
- Spring AI tool：2
- 模型成本估算：10
- Token：69956（input/output/total 三种 token_type 合计）

## 验证

- 根项目 `mvn -q test`：通过。
- MCP 子项目 `mvn -q test`：通过。
- `scripts/test-observability.ps1`：全部通过。
- Prometheus target `ai-agent`：UP。
- Grafana 13.1.0：database=ok，Dashboard provisioning 成功。
- 浏览器实测：12 个面板正常渲染，TTFT 显示 1.81s，Jaeger 链接可见。

## 已知外部限制

本机 `PEXELS_API_KEY` 被 Pexels 返回认证失败。MCP 工具注册、调用、Trace 与指标已贯通，
但候选图片为空。替换有效 Key 后重启即可恢复业务结果，无需再改 MCP 代码。
