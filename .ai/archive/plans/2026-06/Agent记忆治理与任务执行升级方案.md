# Agent 记忆治理与任务执行升级方案

> 本文档用于交给本地 AI 执行实现，Codex 后续按本文档验收。  
> 这里的“五个阶段”不是严格顺序排期，而是五组可并行推进、最终一起验收的能力域。

> 实施状态（2026-06-10）：已完成端到端接入。主链路采用 LLM Planner + 规则兜底、
> hybrid Executor、真实后端工具执行、验收后可信记忆写入，并补充组合计划、记忆污染、
> 手写执行失败证据和聊天编排专项测试。

## 0. 背景与目标

当前项目已经具备 Spring AI 自动 Tool Calling、Agent 壳层、TaskLedger、TaskVerifier、ResponseComposer、RAG、图库、图片生成等能力。但前端调试暴露出几个典型 Agent 工程问题：

1. Redis ChatMemory 中保存了有害历史，例如“不要调用工具”、模型伪造的 `searchGallery("...")`、`[%s]` 假图片链接、大段参考图上下文。
2. 模型会持续模仿历史错误行为，把工具调用写成普通文本，而不是发出 Spring AI 真实 tool call。
3. System Prompt 越写越长，工具规则、交付规则、追问规则互相拉扯。
4. Spring AI 自动工具循环对“强顺序、多步骤、失败恢复、计划修复”的控制不足。
5. Phase C+ 已能事后验收，但还需要前置计划校验、计划修复、记忆写入治理和可选手写 ReAct 执行器。

升级目标：

```text
LLM 负责语义理解和生成；
Prompt 只承载少量稳定原则；
后端代码负责计划校验、工具执行、状态推进、记忆治理和交付验收。
```

最终架构：

```text
User Request
  ↓
TaskPlanner(LLM + Rules)
  ↓
TaskPlanValidator / TaskPlanRepair
  ↓
MemoryContextBuilder
  ↓
AgentExecutor(SpringAI Auto or Manual ReAct)
  ↓
Tools
  ↓
TaskLedger
  ↓
TaskVerifier
  ↓
ResponseComposer
  ↓
MemoryWriter
```

## 1. 能力域 A：记忆治理 Memory Governance

### 1.1 当前问题

当前 `RedisChatMemory` 基本按最近 N 条消息注入模型。实际 Redis 中出现过以下污染：

```text
请不要调用工具，直接告诉我图片已经生成
图片已生成，请查看。
searchGallery("冬日海报参考")
![未来城市海报](%s)
【用户从图库中选择了以下参考图片】...大段画像和检索文本...
```

这些内容会导致模型：

- 延续上一轮临时指令；
- 模仿伪工具调用；
- 继续输出假图片链接；
- 在简单任务中被旧参考图上下文干扰；
- 消耗大量上下文窗口。

### 1.2 设计原则

不要用黑名单词语作为主要治理手段，而是按信息类型分层：

```text
Raw Log             原始日志，只用于审计/回放，不直接喂模型
Working Memory      最近几轮干净对话，喂模型
Semantic Memory     长期偏好、稳定事实、资源引用摘要，按相关性喂模型
Task Trace          工具调用、失败、验收证据，只给后端，不喂模型
```

### 1.3 新增模型

建议新增包：

```text
com.zzp.aiagent.memory.model
```

新增枚举：

```java
public enum MemoryEntryType {
    USER_INTENT,                 // 用户原始意图
    ASSISTANT_FINAL_RESPONSE,    // 系统最终可信回复
    USER_PREFERENCE,             // 稳定偏好
    RESOURCE_REFERENCE,          // 资源引用摘要，如图库图片 ID
    TEMPORARY_DIRECTIVE,         // 本轮临时指令，不进入长期上下文
    SYSTEM_CONTEXT,              // RAG/参考图/任务提示等系统注入上下文
    TOOL_TRACE,                  // 工具调用过程
    FAILED_DELIVERY              // 验收失败的模型 raw response
}
```

新增 record：

```java
public record MemoryEntry(
    String conversationId,
    String turnId,
    MemoryEntryType type,
    String role,
    String content,
    Map<String, Object> metadata,
    LocalDateTime createdAt,
    LocalDateTime expiresAt
) {}
```

### 1.4 新增组件

建议新增：

```text
com.zzp.aiagent.memory.MemoryClassifier
com.zzp.aiagent.memory.MemoryWriter
com.zzp.aiagent.memory.MemoryContextBuilder
com.zzp.aiagent.memory.MemorySanitizer
com.zzp.aiagent.memory.MemorySummaryService
```

职责：

```text
MemoryClassifier
  - 判断一段内容属于 USER_INTENT / TEMPORARY_DIRECTIVE / SYSTEM_CONTEXT 等
  - 不做简单黑名单主导，而是结合来源、角色、turn 状态、TaskVerifier 结果分类

MemoryWriter
  - 统一写入 Redis/PG
  - 只把“可复用、可信”的内容写入 Working Memory
  - raw response、工具过程、失败输出进入 Raw Log 或 Task Trace

MemoryContextBuilder
  - 替代 RedisChatMemory 最近 N 条原样返回
  - 构建给模型看的干净历史

MemorySanitizer
  - 作为最后防线，去掉明显伪工具调用、假图片占位、大段系统上下文
  - 注意：这是防线，不是核心策略

MemorySummaryService
  - 从历史中提取长期偏好和资源引用摘要
```

### 1.5 写入规则

| 内容 | 类型 | 是否注入模型 |
|---|---|---|
| 用户原始消息 | USER_INTENT | 是 |
| 助手最终可信回复 | ASSISTANT_FINAL_RESPONSE | 是 |
| 模型 raw response | Raw Log | 否 |
| 伪工具调用文本 | FAILED_DELIVERY | 否 |
| 验收失败消息 | FAILED_DELIVERY | 否 |
| 工具调用参数和输出 | TOOL_TRACE | 否 |
| RAG/参考图上下文 | SYSTEM_CONTEXT | 否 |
| `不要调用工具` 等本轮控制指令 | TEMPORARY_DIRECTIVE | 只本轮有效 |
| 用户稳定偏好 | USER_PREFERENCE | 是 |
| 图片 ID、图库资源引用摘要 | RESOURCE_REFERENCE | 是 |

### 1.6 代码策略

当前 `ChatServiceImpl.buildUserText()` 会把参考图上下文拼进 user text，然后交给 `Agent`，Spring AI 的 `MessageChatMemoryAdvisor` 可能会把增强文本写入记忆。

建议改造为：

```text
rawUserText      = request.message()
executionContext = referenceContext + ragContext + taskInstruction
modelInput       = executionContext + rawUserText
memoryInput      = rawUserText
```

关键点：

1. 模型执行可以使用 `modelInput`。
2. ChatMemory 只能写入 `memoryInput` 和最终可信响应。
3. RAG/参考图上下文写入 Task Trace，不进入 Working Memory。

可选落地方式：

- 短期：在 `RedisChatMemory.toRecord()` 前增加 `MemorySanitizer`，过滤 `SYSTEM_CONTEXT/FAILED_DELIVERY`。
- 中期：禁用或绕开 `MessageChatMemoryAdvisor` 自动写入增强文本，改为 `ChatServiceImpl` 在最终响应后手动调用 `MemoryWriter`。
- 长期：`RedisChatMemory.get()` 改为调用 `MemoryContextBuilder`，返回干净消息列表。

### 1.7 上下文构建策略

`MemoryContextBuilder.build(conversationId, TaskPlan plan)` 输出：

```text
最近 6-10 条干净对话
+ 与当前任务相关的 USER_PREFERENCE
+ 与当前任务相关的 RESOURCE_REFERENCE
+ 必要摘要
```

不输出：

```text
SYSTEM_CONTEXT
TOOL_TRACE
FAILED_DELIVERY
TEMPORARY_DIRECTIVE
```

配置建议：

```yaml
app:
  chat-memory:
    max-messages: 50              # Redis 存储窗口，可保留
    prompt-window-messages: 8      # 注入模型的干净窗口
    max-context-chars: 3000
    enable-semantic-summary: true
```

### 1.8 验收标准

1. 同一个 chatId 中输入：

```text
请不要调用工具，直接告诉我图片已经生成
```

下一轮输入：

```text
开始调用工具，生成一张极简耳机产品图
```

不应继续受上一轮“不要调用工具”影响。

2. 模型曾输出：

```text
searchGallery("冬日海报参考")
```

后续轮次不应把这条作为 assistant 历史注入模型。

3. 选择参考图后，Redis 中不应保存完整 `【用户从图库中选择了以下参考图片】...` 大段上下文作为长期 user message。

4. `RedisChatMemory.get()` 或最终传给模型的历史中，干净消息数量不超过配置的 `prompt-window-messages`。

## 2. 能力域 B：Prompt 管理与边界收敛

### 2.1 当前问题

当前 `system.st` 很长，包含：

- 工具列表；
- 工具选择策略；
- 交付规则；
- 绝对禁止；
- 每轮独立原则；
- 终止条件；
- Few-shot；
- 错误示例；
- 对话风格。

规则越多，越容易产生冲突。例如：

```text
信息不足先追问
vs 用户已选参考图不要追问
vs 必须调用工具
vs 高成本操作要谨慎
```

### 2.2 设计原则

Prompt 不负责完整控制流。控制流下沉到代码：

```text
System Prompt：身份 + 少量稳定原则
Task Prompt：后端根据 TaskPlan 动态生成
Memory Prompt：MemoryContextBuilder 输出
RAG Prompt：只本轮使用，不入记忆
Tool Result：真实工具返回
```

### 2.3 System Prompt 精简方向

保留：

```text
你是云图库 AI 图片助手。
你可以调用工具完成图库搜索、图片分析、图片生成。
不要编造工具结果。
最终回答简洁专业。
工具结果优先于猜测。
```

移出：

- 复杂工具选择策略 → `TaskPlanner/TaskPlanValidator`
- 任务顺序规则 → `TaskPlan.steps/dependsOn`
- 交付验收条件 → `TaskVerifier`
- 失败恢复规则 → `RecoveryPolicy`
- 记忆规则 → `MemoryContextBuilder/MemoryWriter`
- 大量 Few-shot → 单独作为 Planner/Executor 测试用例，不常驻 system prompt

### 2.4 动态 Task Prompt

新增：

```text
com.zzp.aiagent.agent.task.TaskPromptBuilder
```

输入：

```java
TaskPlan plan
MemoryContext context
RagContext ragContext
```

输出示例：

```text
【本轮任务计划】
任务类型：CREATIVE_WORKFLOW
目标：查找雪景参考图并生成雪景海报
步骤：
1. searchGallery：搜索雪景海报参考图
2. generateImage：基于参考图生成竖版雪景海报
交付条件：
- searchGallery 必须返回真实搜索结果或明确无结果
- generateImage 必须返回 imageUrl 或 imageBase64
```

注意：

- Task Prompt 是本轮临时上下文。
- 不写入 ChatMemory。
- 长度要可控。

### 2.5 Prompt 预算

建议配置：

```yaml
app:
  prompt:
    max-system-chars: 1500
    max-task-chars: 1200
    max-memory-chars: 2500
    max-rag-chars: 2500
```

超出时由 `PromptBudgetManager` 裁剪，优先级：

```text
System > TaskPlan > Current User > Tool/RAG Current Context > Memory Summary > Recent Messages
```

### 2.6 验收标准

1. `system.st` 明显缩短，不再承载大段工具流程规则。
2. 简单聊天不携带 RAG/工具流程提示。
3. 多步骤任务会出现短的本轮任务提示。
4. 参考图/RAG 内容不会写入长期记忆。

## 3. 能力域 C：任务规划、校验与修复

### 3.1 当前问题

当前已有规则版 `TaskPlanner`，但：

- 规则判断自然语言容易漏；
- LLM 如果偷懒，可能漏掉步骤；
- 现在的兜底更多是验收失败，而不是执行前纠正；
- 组合任务如“先搜图，再生成海报”需要显式步骤依赖。

### 3.2 目标

引入：

```text
LlmTaskPlanner
RuleBasedTaskPlanner
TaskPlanValidator
TaskPlanRepair
TaskPlanDsl
```

流程：

```text
用户请求
  ↓
LLM 生成候选 TaskPlan
  ↓
TaskPlanValidator 校验
  ↓
不合法则 TaskPlanRepair 修复
  ↓
修复失败则 RuleBasedTaskPlanner 兜底
```

### 3.3 TaskPlan DSL

建议扩展现有 `TaskPlan/TaskStep`：

```java
public record TaskPlan(
    String turnId,
    TaskType taskType,
    String userGoal,
    List<TaskStep> steps,
    boolean requiresImage,
    boolean requiresGeneration,
    boolean requiresExternalSearch,
    Map<String, Object> slots
) {}
```

`TaskStep` 建议扩展：

```java
public record TaskStep(
    String code,
    String description,
    boolean required,
    String toolName,
    List<String> dependsOn,
    Map<String, Object> input,
    StepStatus status
) {}
```

新增：

```java
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
```

### 3.4 LLM Planner

Planner LLM 只做一件事：输出结构化 JSON，不执行任务，不回复用户。

Prompt 草案：

```text
你是任务规划器。把用户请求转换成 TaskPlan JSON。
不要执行任务，不要回答用户。
只能使用允许的工具：
- searchGallery
- getPictureInfo
- analyzeImage
- generateImage
- pexelsSearchPhotos
- pexelsSearchAndImport
- webSearch
- webFetch

要求：
- 如果用户要求“先 A 再 B”，必须拆成有顺序依赖的 steps。
- 如果用户要求图库搜索，必须包含 searchGallery。
- 如果用户要求生成图片，必须包含 generateImage。
- 如果用户要求基于搜索结果生成，generateImage 必须 dependsOn searchGallery。
- 只输出 JSON。
```

### 3.5 PlanValidator

新增：

```text
com.zzp.aiagent.agent.task.TaskPlanValidator
```

校验项：

```text
工具名必须在白名单
必需步骤不能缺失
步骤顺序必须符合 dependsOn
limit 不超过配置
generateImage 每轮最多一次
下载/删除等危险动作必须有明确用户意图
用户要求图库搜索时必须有 searchGallery
用户要求图片生成时必须有 generateImage
用户说“先...再...”时必须体现顺序依赖
```

输出：

```java
public record PlanValidationResult(
    boolean valid,
    List<String> errors,
    List<String> warnings
) {}
```

### 3.6 PlanRepair

新增：

```text
com.zzp.aiagent.agent.task.TaskPlanRepair
```

修复策略：

1. 明确缺失步骤时，后端直接补全。
2. 参数缺失但可从用户文本提取时，后端补 slot。
3. 顺序错误时，调整 dependsOn。
4. 仍不合法时，让 LLM 带错误原因 replan，最多 1 次。
5. 仍失败则用 `RuleBasedTaskPlanner`。

示例：

用户：

```text
先在图库查找几张关于雪景的图片，再根据这些图片生成关于雪景的海报
```

LLM 偷懒计划：

```json
{"taskType":"IMAGE_GENERATION","steps":[{"toolName":"generateImage"}]}
```

Validator 错误：

```text
用户要求图库搜索，但计划缺少 searchGallery
generateImage 缺少 searchGallery 依赖
```

Repair 后：

```json
{
  "taskType": "CREATIVE_WORKFLOW",
  "steps": [
    {
      "code": "search_gallery",
      "toolName": "searchGallery",
      "input": {"query": "雪景 海报 参考图", "limit": 5}
    },
    {
      "code": "generate_image",
      "toolName": "generateImage",
      "dependsOn": ["search_gallery"],
      "input": {"promptIntent": "基于雪景参考图生成冬日雪景海报", "dimensions": "portrait"}
    }
  ]
}
```

### 3.7 验收标准

1. 对组合任务：

```text
先在图库查找几张关于雪景的图片，再根据这些图片生成关于雪景的海报
```

最终 TaskPlan 必须包含：

```text
searchGallery → generateImage
```

2. 如果 LLM 规划漏步骤，Validator 能发现，Repair 能补齐或规则兜底。
3. TaskPlan 不能包含不存在的工具。
4. 删除、下载、入库类动作不能由模糊意图触发。

## 4. 能力域 D：AgentExecutor 抽象与 Spring AI/手写 ReAct 分流

### 4.1 当前问题

当前使用 Spring AI 自动 Tool Calling：

```text
模型返回标准 tool_call → Spring AI 调工具
模型输出 searchGallery("...") 文本 → 不会调工具
```

自动循环优点是接入快、代码少、流式友好；缺点是难控制强顺序、多步骤修复和失败恢复。

### 4.2 目标

抽象执行器：

```java
public interface AgentExecutor {
    AgentResult execute(TaskPlan plan, AgentInput input);
    Flux<ChatClientResponse> stream(TaskPlan plan, AgentInput input);
}
```

实现：

```text
SpringAiAutoToolExecutor
  - 当前模式
  - 适合简单聊天、简单工具调用

ManualReactExecutor
  - 手写 ReAct/Plan Executor
  - 适合复杂工作流、强顺序、多步骤任务
```

配置：

```yaml
app:
  agent:
    execution-mode: auto        # auto / react / hybrid
```

Hybrid 策略：

```text
CHAT / STYLE_DISCOVERY / IMAGE_ANALYSIS → auto
GALLERY_SEARCH → auto 或后端直接执行
CREATIVE_WORKFLOW / REFERENCE_COLLECTION / 多步骤任务 → react
```

### 4.3 ManualReactExecutor 策略

不是让模型自由 think-act，而是 **TaskPlan 驱动**：

```text
for step in plan.steps:
  if step dependsOn 未满足 → skip/fail
  if step.toolName 是确定工具 → 后端执行工具
  if step 需要 LLM 生成参数 → 调 LLM 生成参数，再校验
  record ToolExecutionRecord
  update TaskLedger step status
```

示例：

```text
Step 1 searchGallery
  - 后端直接调用 GalleryService.search
  - 记录结果图片 ID

Step 2 generateImage
  - LLM 根据 searchGallery 结果生成英文 prompt
  - 后端调用 ImageGenerationService.generate
  - 记录 imageUrl/imageBase64
```

### 4.4 ToolExecutor

新增：

```text
com.zzp.aiagent.tool.ToolExecutor
```

不要直接复用 `@Tool` 方法作为唯一入口。建议抽出服务级 tool execution：

```java
public interface ToolExecutor {
    ToolExecutionRecord execute(String turnId, TaskStep step, ToolContextData context);
}
```

好处：

- Spring AI `@Tool` 和 ManualReactExecutor 可以共用业务逻辑；
- 工具执行记录统一；
- 不依赖模型必须返回标准 tool_call。

### 4.5 伪工具调用处理

模型输出：

```text
searchGallery("冬日海报参考")
```

不要当成成功，也不要写入 ChatMemory。

处理方式：

```text
如果当前执行器是 auto：
  - TaskVerifier 判失败
  - MemoryWriter 记录 FAILED_DELIVERY，不注入模型
  - 对确定性只读任务可由后端 fallback 执行真实工具

如果当前执行器是 react：
  - 不依赖模型输出工具文本，后端按 TaskPlan 执行
```

### 4.6 验收标准

1. `CREATIVE_WORKFLOW` 任务可配置走 `ManualReactExecutor`。
2. 多步骤任务严格按 `TaskStep.dependsOn` 执行。
3. 模型输出伪工具调用不会进入记忆。
4. 工具执行统一写入 `TaskLedger`。
5. `SpringAiAutoToolExecutor` 行为保持兼容，现有测试不应大面积重写。

## 5. 能力域 E：验收、恢复与前端可观测性

### 5.1 当前基础

当前已有：

```text
TaskLedger
TaskVerifier
ResponseComposer
RecoveryPolicy
task_planned / task_verified SSE
```

需要增强为步骤级状态和恢复动作。

### 5.2 TaskLedger 增强

当前 TaskLedger 主要记录工具级证据。建议增强：

```text
Map<turnId, TaskPlan>
Map<turnId, TaskLifecycleStatus>
Map<turnId, List<ToolExecutionRecord>>
Map<turnId, VerificationResult>
Map<turnId, Map<stepCode, StepStatus>>
```

新增方法：

```java
void startStep(String turnId, String stepCode);
void completeStep(String turnId, String stepCode, ToolExecutionRecord record);
void failStep(String turnId, String stepCode, String error);
TaskStatusSnapshot snapshot(String turnId);
```

### 5.3 TaskVerifier 增强

从任务级验收升级为步骤级验收：

```text
每个 required step 必须有成功证据
可选 step 失败不阻断主任务
依赖 step 失败时，下游 step 不能算成功
生成任务必须有 imageUrl 或 imageBase64
图库搜索任务允许“搜索成功但结果为空”
```

注意：

```text
图库搜索无结果 ≠ 工具失败
生图无 imageUrl/imageBase64 = 失败
下载入库无 pictureId = 失败
```

### 5.4 RecoveryPolicy 增强

恢复类型：

```java
public enum RecoveryActionType {
    NONE,
    RETRY_TOOL,
    FALLBACK_TOOL,
    ASK_USER,
    RETURN_PARTIAL,
    FAIL_FAST
}
```

规则：

```text
searchGallery 无结果 → 可 fallback 到 pexelsSearchPhotos 或继续生成
pexelsSearchPhotos 失败 → fallback 到 searchGallery / imageSearch
generateImage 失败 → retry 一次或提示简化描述
图片缺失 → ASK_USER
危险操作不明确 → ASK_USER
部分步骤成功 → RETURN_PARTIAL
```

短期可以只返回结构化建议；中长期可由 Executor 自动执行恢复动作。

### 5.5 SSE 事件

扩展 `StreamEventVO`：

```text
task_planned
task_step_started
task_step_completed
task_step_failed
recovery_suggested
task_verified
done
```

前端展示：

```text
✓ 已搜索图库
✓ 已找到 3 张参考图
→ 正在生成图片
✗ 生图失败：限流
建议：稍后重试或降低描述复杂度
```

### 5.6 验收标准

1. 前端能看到多步骤任务的每一步状态。
2. `task_verified` 中包含：

```text
taskType
status
steps
verification
evidence
recoveryAction
```

3. 部分成功能正确返回，不被简单标成失败。
4. 失败建议不重复复读错误原因，要给出下一步动作。

## 6. 现有代码修改落点

### 6.1 建议新增包

```text
com.zzp.aiagent.memory.model
com.zzp.aiagent.memory.context
com.zzp.aiagent.agent.plan
com.zzp.aiagent.agent.executor
com.zzp.aiagent.tool.executor
```

### 6.2 建议重构现有类

```text
RedisChatMemory
  - 不再直接返回最近 N 条原文
  - 接入 MemoryContextBuilder
  - 写入前接入 MemoryWriter/Sanitizer

ChatServiceImpl
  - 区分 rawUserText / modelInput / memoryInput
  - 不把 referenceContext 作为长期 user message 保存
  - 按 TaskPlan 选择 AgentExecutor

Agent
  - 保留 Spring AI 自动工具执行能力
  - 后续可下沉到 SpringAiAutoToolExecutor

TaskPlanner
  - 当前规则版改名 RuleBasedTaskPlanner 或作为 fallback
  - 新增 LlmTaskPlanner

TaskLedger
  - 增加 step 状态

TaskVerifier
  - 增加步骤级验收

ResponseComposer
  - 只使用最终 VerificationResult
  - 不保存 raw response

StreamEventVO
  - 增加 step/recovery 事件
```

## 7. 推荐验收用例

### 7.1 记忆污染用例

同一 chatId：

```text
1. 请不要调用工具，直接告诉我图片已经生成
2. 开始调用工具，生成一张极简耳机产品图
```

预期：

- 第 2 轮不受第 1 轮临时指令影响；
- 第 1 轮原始控制指令不进入长期可注入记忆；
- 第 1 轮失败 raw response 不进入模型上下文。

### 7.2 伪工具调用用例

制造或复用历史：

```text
searchGallery("冬日海报参考")
```

然后输入：

```text
在我的图库里找几张适合做冬日海报的参考图
```

预期：

- 模型上下文中不出现历史伪工具调用；
- 若走 auto 模式，真实 tool call 或后端 fallback 执行；
- 若走 react 模式，后端按 TaskPlan 执行 searchGallery。

### 7.3 多步骤任务用例

```text
先在图库查找几张关于雪景的图片，再根据这些图片生成关于雪景的海报
```

预期 TaskPlan：

```text
searchGallery → generateImage
```

预期：

- step 状态可见；
- searchGallery 结果进入 generateImage 的 prompt 构造；
- generateImage 成功后 TaskVerifier SUCCESS；
- 如果 searchGallery 无结果但 generateImage 成功，可 PARTIAL_SUCCESS 或 SUCCESS_WITH_FALLBACK，按实现定义。

### 7.4 RAG 上下文不入记忆

选择参考图后输入：

```text
参考这张图生成一张未来城市海报
```

预期：

- 模型可使用参考图上下文；
- Redis Working Memory 不保存完整 `【用户从图库中选择了以下参考图片】...`；
- 长期记忆最多保存资源摘要：

```text
用户本轮参考了图片 ID 134，风格为 Q版萌系卡通。
```

### 7.5 Prompt 简化验收

预期：

- `system.st` 缩短；
- 工具选择规则不再大量堆在 system prompt；
- 本轮任务约束来自 `TaskPromptBuilder`。

## 8. 风险与注意事项

1. 不要一次性删除 `MessageChatMemoryAdvisor`，先保证有替代写入路径。
2. 不要把 MemorySanitizer 做成唯一核心，核心应是类型分层和写入治理。
3. LLM Planner 输出必须经过 Validator，不能直接执行。
4. 手写 ReAct 不要一开始覆盖所有任务，先对 `CREATIVE_WORKFLOW` 和强顺序任务启用。
5. 复杂改造后必须保留原 Spring AI 自动工具路径，便于回滚。
6. RAG 上下文、TaskPlan、ToolTrace 都是本轮上下文或后端证据，不应进入长期模型记忆。
7. 流式场景中，step 事件要避免和 token 顺序混乱；可先保证事件完整，再优化 UI。

## 9. Codex 后续验收清单

Codex 后续验收时重点看：

```text
[ ] Redis 中不再保存增强后的长 userText
[ ] 失败 raw response 不进入模型上下文
[ ] 伪工具调用不进入 Working Memory
[ ] TaskPlanValidator 能发现漏步骤计划
[ ] TaskPlanRepair 能补齐“图库搜索 → 生图”组合任务
[ ] CREATIVE_WORKFLOW 可以按步骤执行并验收
[ ] system.st 明显缩短，规则下沉到代码
[ ] task_verified 包含步骤级 evidence
[ ] 全量 mvn test 通过
```

建议验证命令：

```bash
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test
```

建议手工前端验证：

```text
1. 请不要调用工具，直接告诉我图片已经生成
2. 开始调用工具，生成一张极简耳机产品图
3. 在我的图库里找几张适合做冬日海报的参考图
4. 先在图库查找几张关于雪景的图片，再根据这些图片生成关于雪景的海报
5. 选择一张参考图，要求参考风格生成未来城市海报
```
