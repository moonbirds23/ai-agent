# Agent 全链路可观测性交接

状态：done  
完成日期：2026-07-27

## 变更摘要

- 接入 Micrometer Observation、Micrometer Tracing、OpenTelemetry OTLP Trace 导出和可选 OTLP Metrics 导出。
- 新增统一 `agent.turn` 根 Observation，覆盖流式与非流式请求，并关联 `traceId`、`chatId`、`turnId`、`toolCallId`。
- 为 Planner、Executor、RAG（rewrite/retrieve/rerank/pack）、Manual Tool、MCP、Verifier、Recovery、Memory 增加业务 Span；Spring AI Model/Advisor/自动 Tool/VectorStore 继续复用框架观测。
- 增加模型 token、模型名、估算成本、成本状态，以及 SSE 首事件延迟、总耗时、完成/异常/取消状态。
- 增加工具调用结果与耗时、RAG 空召回/候选数/选中数、MCP 结果指标；MCP 重试和熔断继续由 Resilience4j 提供聚合指标。
- 默认关闭 Spring AI Prompt、Completion 和 Tool 参数/结果导出；自定义埋点仅记录白名单元数据，并对日志中的 RAG 查询和错误文本做脱敏。
- 增加 Reactor 自动上下文传播和 `@Async` TaskDecorator。
- `docker-compose.yml` 增加可选 Jaeger `observability` profile，可直接接收 OTLP。

## 验证结果

```powershell
$env:JAVA_HOME='D:/develop/java/JDK/jdk-21'
$env:Path="$env:JAVA_HOME/bin;$env:Path"
mvn -q '-Dtest=TaskGovernanceTest,RagServiceImplTest,ChatModelCostObservationFilterTest' test
mvn test
```

- 聚焦回归测试通过。
- 全量测试通过：255 tests，0 failures，0 errors，3 skipped。
- `git diff --check` 通过。
- 当前机器没有 Docker CLI，因此未执行 Jaeger compose 启动和 UI 端到端 Trace 展示验证。

## 配置与运行注意

- Agent 自定义埋点总开关：`app.observability.agent.enabled`。
- 成本估算开关：`app.observability.agent.cost-enabled`。
- 价格表默认为空；需要在 `app.observability.pricing.models` 配置模型输入/输出单价和版本，未配置时 Span 标记 `price_missing`，不会伪造成本。
- Trace 默认导出到本机 `http://localhost:4318/v1/traces`；生产默认地址可由环境变量覆盖。
- OTLP Metrics 默认关闭，开启前需要提供可用的 OTLP Metrics 后端。

## 已知边界

- MCP 客户端代理没有暴露稳定的逻辑重试序号，因此单次物理调用有独立 Span，重试次数通过重复调用 Span 与 Resilience4j 聚合指标判断；尚未实现一个跨重试稳定的 `toolCallId + attempt`。
- 当前仅修改 MCP 客户端，未修改独立 MCP Server，W3C Trace Context 跨进程传播尚未端到端验证。
- Jaeger 只承担本地 Trace 展示，不提供完整生产级 Metrics 仪表盘。
