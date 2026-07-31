# Agent 可观测性本地使用指南

## 一键启动

首次安装 Prometheus 与 Grafana：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-observability.ps1
```

以后启动 PostgreSQL、Redis 后，执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Observability
```

该命令会启动或复用：

| 服务 | 地址 |
|---|---|
| AI Agent | http://localhost:8231/api/ |
| MCP Server | http://localhost:8232/sse |
| Jaeger | http://localhost:16686/search?service=aiagent |
| Prometheus | http://localhost:9090/targets |
| Grafana | http://localhost:3000/d/agent-observability |

Prometheus、Grafana、Jaeger 的程序和运行数据保存在 Git 忽略的 `var/` 下。

## 自动冒烟验收

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-observability.ps1
```

脚本会执行一轮真实 Chat 请求，并验证：

- 应用健康；
- Micrometer Prometheus 指标；
- Prometheus 抓取目标和 Agent 查询；
- Grafana 健康与预置 Dashboard；
- Jaeger 服务和近期 Trace。

真实 RAG、生图、MCP 和流式链路仍应按需执行，避免无意义消耗外部 API 配额。

## Grafana 面板

`AI Agent Observability` 默认每 5 秒刷新，展示：

- Agent 请求速率、平均总耗时、平均首 Token 延迟；
- 自动/手动工具成功率；
- MCP 调用结果、重试与熔断状态；
- RAG 空召回率、候选/选中数量、Retrieve/Rerank 耗时；
- Token 用量、价格覆盖和估算成本；
- HTTP 请求与 JVM 堆内存。

Dashboard 顶部 `Open Jaeger traces` 可跳转到 Jaeger 查看单次请求的 Span 树。

## 隐私边界

默认 Trace 和日志不记录完整 Prompt、模型回复、工具参数、搜索词和图片 URL。
允许记录的是长度、数量、模型名、状态、耗时、标识符与脱敏摘要。

本地 `chatId`、`turnId`、`toolCallId` 会进入 Trace，便于跨阶段关联；它们不进入
Prometheus 标签，避免高基数。

## 当前限制

- `glm-4-flash` 官方价格为免费，因此文本模型估算成本会显示 `0 CNY`，不是指标失效。
- Spring AI 1.0.0 + MCP SDK 0.10.0 的 SSE Transport 不能可靠地为每次
  `tools/call` 传播 W3C Trace Context。当前由 MCP Server 创建独立 Trace，并通过
  Span Link 表达它与主应用 MCP Client Span 的因果关系，同时记录
  `agent.mcp.trace_context_propagated=false`；不得用 `toolCallId`、`turnId` 或日志字段
  冒充同一 Trace 的父子链。完整实测案例见
  [`docs/observability/agent-tracing.md`](../observability/agent-tracing.md)。
- 2026-07-30 已使用本机 Pexels Key 实测返回 3 条真实候选；Key 只通过环境变量注入，
  不进入代码、Trace、截图或文档。
- Jaeger 使用本地内存存储，Jaeger 进程重启后历史 Trace 会清空。
