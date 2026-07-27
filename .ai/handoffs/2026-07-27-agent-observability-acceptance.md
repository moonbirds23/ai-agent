# Agent 全链路可观测性端到端验收

状态：done  
验收日期：2026-07-27

## 决策

结论：`调整`。基础 Agent Trace、手动工具链、流式首响应/取消和 Collector 降级成立，但 RAG、MCP 成功链、聚合 Metrics、成本和日志隐私仍未达到完整 P0 验收条件。

## 实测结果

| 场景 | 结果 | 证据 |
|---|---|---|
| 非流式聊天 | 通过 | Trace `936b3a324ee22ceca8b27321d6051117`，12 Span，无错误 |
| 流式聊天 | 通过 | Trace `a14f34b48e677f19a913e8a486c98747`，16 Span，首 Token 约 1.53 秒 |
| 图库自动 Tool | 通过 | Trace `16acf07f43e5cbb5af128cc1c5b0c1c7`，含 Tool、Embedding、Verifier |
| ManualReact 生图 | 通过 | Trace `04416f7936f6e0727e5bd5284af63ad5`，21 Span，两个 `agent.tool` 和独立 `toolCallId` |
| MCP 与 Retry | 部分通过 | Trace `f262411226aa6e0410a7a31c8704ec97` 出现两次 `agent.mcp.call`，但均因 Tool 未注册失败 |
| 流式取消 | 通过 | Trace `02fbc09531d2c7962808012af3707586`，`agent.outcome=cancelled` |
| Collector 不可用 | 通过 | Jaeger/OTLP 停止时聊天仍返回 HTTP 200、业务码 0，约 1.84 秒 |
| Jaeger 前端 | 通过 | Trace `c691dd1c8e46adf9e437cd4e763c4614` 可展示 12 Span、6 层深度 |
| 自动化回归 | 通过 | 257 tests，0 failures，0 errors，3 skipped |

## 阻断问题

1. `RagService.buildContext()` 没有真实 Chat 入口调用。RAG 单元测试通过，但图库和 ManualReact 实测 Trace 都没有 `agent.rag.*`。
2. MCP Server 没有提供 `ToolCallbackProvider`，客户端调用 `pexelsSearchPhotos` 返回 `Tool not found`。
3. OTLP Metrics 默认关闭，Actuator 只暴露 `health,info`；工具成功率、RAG 空召回率和 CircuitBreaker 聚合状态当前无法展示。
4. 模型价格表为空，Trace 标记 `agent.cost.status=price_missing`。
5. `LoggingAdvisor` 会记录完整实际 Prompt 和回复正文，不符合默认不采集/脱敏要求。
6. MCP Retry 的两次物理调用使用不同 `toolCallId`，无法按一个逻辑调用直接聚合 attempt。

## 已确认边界

- Trace 中未发现用户 Prompt、模型正文或工具实参标签；Spring AI Trace 敏感内容默认关闭有效。
- 日志层仍存在正文泄漏，因此整体隐私验收不通过。
- Jaeger 使用内存存储，重启会清空已有 Trace。
- 流式业务失败会被 Recovery 转换为正常 SSE，本次未获得最终 `agent.turn=error` 的真实运行样本。

## 下一步验收门槛

- 将 RAG 接入真实 Chat/生图链并出现 rewrite/retrieve/rerank/pack Span。
- 注册并实测 MCP Server 工具，获得 MCP 成功 Trace。
- 提供可查询的 Metrics 后端或本地 Prometheus 端点。
- 配置模型价格并验证估算成本。
- 默认关闭或脱敏 Prompt/Completion 日志。
- 修复后重新执行本报告中的端到端用例。
