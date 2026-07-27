# Agent 全链路可观测性

状态：done  
创建日期：2026-07-27

## 背景

当前项目只有日志、`AgentContext`、`TaskLedger` 和独立 `RagTrace`，无法通过一次请求的统一调用链查看 Planner、Model、RAG、MCP、Tool、Verifier、Recovery 和 Memory 的关系、耗时与结果。

## 目标

基于 Micrometer Observation、OpenTelemetry 和 OTLP，为流式及非流式 Agent 请求生成完整 Trace，并输出低基数聚合 Metrics。通过 `traceId` 关联 `chatId`、`turnId` 和 `toolCallId`，默认不采集敏感 Prompt、工具参数和模型正文。

## 范围

- OTLP Trace 和 Micrometer Metrics 导出。
- `agent.turn` 根 Observation。
- Planner、Executor、RAG、Manual Tool、MCP、Verifier、Recovery、Memory Span。
- 复用 Spring AI 的 Model、Advisor、自动 Tool 和 VectorStore Observation。
- Token、模型、估算成本、首响应延迟、总耗时。
- Tool 成功/失败、Retry、CircuitBreaker 和 RAG 聚合指标。
- Reactor、SSE cancel 和 `@Async` 上下文传播。
- 脱敏、单元测试、集成测试和本地 Trace 验证。

## 非目标

- PostgreSQL Trace/Event 存储。
- 产品级 Trace 管理页面和完整监控大盘。
- 完整 Prompt、Completion、图片 Base64、工具参数或工具结果存储。
- 修改独立 MCP Server 的内部埋点。

## 关键方案

- Micrometer `ObservationRegistry` 是唯一代码埋点入口，OTel/OTLP 负责标准追踪和导出。
- `TaskLedger` 继续作为执行与验收事实来源，不由 Trace 取代。
- 低基数属性进入 Metrics；`chatId`、`turnId`、`toolCallId` 只进入 Trace。
- `turnId` 在 Planner 之前创建，使整轮调用链共享统一业务标识。
- 流式根 Span 在订阅时开始，并按 complete、error、cancel、timeout 恰好结束一次。
- Spring AI 已有 Span 优先复用，ManualReact、MCP 和 Agent 业务阶段补自定义 Span。
- Provider 未返回 Usage 或价格缺失时明确标记 unavailable，不伪造 Token 或成本。
- 可观测后端不可用时不得影响聊天主链。

## 验收

```powershell
$env:JAVA_HOME="D:/develop/java/JDK/jdk-21"
$env:Path="$env:JAVA_HOME/bin;$env:Path"
mvn test
mvn compile
```

功能验收至少覆盖：

- 自动工具与 ManualReact 两条完整 Trace。
- Planner、Model、RAG、MCP、Tool、Verifier 独立 Span。
- 流式成功、异常和取消状态。
- ID 关联且高基数 ID 不进入 Metrics。
- Token/成本可用与不可用两种状态。
- 首响应延迟、工具成功率、Retry、CircuitBreaker、RAG 空召回率。
- 敏感标记不出现在 Trace、Metrics 和日志。
- Collector 停止时聊天仍能正常完成。

## 风险

- Spring AI 1.0.0 与 1.0 系列最新文档存在属性差异。
- Reactor 或异步线程可能丢失 Observation Context。
- 自动 Tool Span 与自定义 Span 可能重复。
- 智谱流式响应可能缺少 Usage。
- MCP SSE 可能无法传播 W3C Trace Context。
- 错误标签设计可能造成 Metrics 高基数。

## 回滚

- 自定义埋点受 `app.observability.agent.enabled` 控制。
- 禁用 OTLP 导出后业务链路仍可运行。
- 不新增业务数据库迁移，不改变外部响应结构。
