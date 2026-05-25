# 2026.5.25:Advisor 拦截链开发记录

## 概述

基于 Spring AI 1.0.0-M6 的 Advisor 机制，设计并实现了一条 5 节点的拦截链，对每次大模型调用进行前置校验、上下文增强、日志记录和异常兜底。

## 架构设计

```
请求 → ContentGuard(0) → MessageChatMemory(内置) → PromptOptimize(20) → Logging(30) → ExceptionGuard(MAX)
                                                                                                    ↓
                                                                                              大模型调用
```

### 执行顺序说明

| Order | Advisor | 职责 | 为什么是这个顺序 |
|-------|---------|------|------------------|
| 0 | ContentGuard | 输入校验（空值/长度/敏感词） | 非法请求第一时间短路，不消耗下游资源 |
| 内置 | MessageChatMemory | 会话记忆管理 | 在 prompt 改写前注入历史消息 |
| 20 | PromptOptimize | 口语→专业生图 prompt 改写 | 日志记录之前改写，日志能看到最终发往模型的文本 |
| 30 | Logging | 全量日志（请求/响应/耗时） | 在异常兜底之前，确保正常请求被记录 |
| MAX | ExceptionGuard | 异常兜底分两类处理 | 最外层安全网，绝不泄漏 stacktrace 给用户 |

## 各 Advisor 详述

### 1. ContentGuardAdvisor — 输入安全

三层校验，按开销从低到高排列：

1. **空值检查** — `null` 或 `blank` → `InvalidInputException(1001)`
2. **长度检查** — 超过 2000 字符 → `InvalidInputException(1002)` 
3. **敏感词过滤** — 命中关键词列表 → `ContentSafetyException(2001)`

通过抛异常实现短路，异常被下游 ExceptionGuard 捕获转为友好回复。

### 2. PromptOptimizeAdvisor — 提示词增强

- 将口语化描述（如"帮我画一只猫"）扩写为包含画面主体、环境背景、光线氛围、艺术风格、画质描述五个维度的专业生图 prompt
- 改写前将**原始输入**存入 `adviseContext`（key: `originalUserText`），供 LoggingAdvisor 读取——这是跨 Advisor 共享状态的典型用法
- 改写后的 prompt 也写入 `adviseContext`（key: `optimizedPrompt`），供后续扩展（如存入数据库、传递给图模型）

### 3. LoggingAdvisor — 全量日志

经历过一次重要迭代：

**v1（LogTimingAdvisor）**：仅记录耗时，格式为 `[LogTiming] chatId=xxx 耗时=xxxms`。流式场景通过 `doOnComplete` 触发。

**v2（LoggingAdvisor）**：参考外部代码 + Spring AI 官方源码后重构，改进点：

- 引入 `MessageAggregator` 聚合流式响应的完整文本，替代简单的 `doOnComplete`
- 从 `adviseContext` 读取原始输入，与改写后的实际 Prompt 分行打印，便于对比改写效果
- 新增字段：请求时间、调用方式（流式/非流式）、回复字符数、完整回复文本

日志输出示例：
```
[AI-请求] 时间=17:45:50.475 chatId=log-demo 方式=非流式
[AI-请求] chatId=log-demo 原始输入(length=32): draw me a cat sitting on a cloud
[AI-请求] chatId=log-demo 实际Prompt(length=105): 请基于用户的图片需求描述，生成一个优化的...
[AI-响应] chatId=log-demo 耗时=3969ms 回复字符数=375
[AI-响应] chatId=log-demo 回复内容: 当然，这里是为你生成的优化版...
```

`MessageAggregator` 的使用方式：`aggregateAdvisedResponse(flux, consumer)` 透传原始 Flux 中的每个 token 片段（不阻塞 SSE），流正常结束时将片段聚合为完整 `AdvisedResponse` 回调 consumer。

### 4. ExceptionGuardAdvisor — 异常兜底

分两层处理：

- **AiAgentException 及其子类**（已知业务异常）→ WARN 级别，按 errorCode 返回对应的用户友好提示
- **Exception 兜底**（未知异常）→ ERROR 级别打印堆栈，返回统一错误码 `9999`

流式场景使用 Reactor 的 `.onErrorResume(A.class, handler).onErrorResume(Throwable, handler)` 双层级联。**必须先匹配子类再匹配兜底**，否则子类异常会被父类分支吞掉，丢失日志分类。

## 关键技术决策

### 1. M6 API 的不可变 Record 约束

`AdvisedRequest` 和 `AdvisedResponse` 都是 Java Record，修改 `adviseContext` 时必须先复制再 put：

```java
Map<String, Object> ctx = new HashMap<>(request.adviseContext());
ctx.put("key", value);
return AdvisedRequest.from(request).adviseContext(ctx).build();
```

直接修改会违反 Record 语义，且 `Builder.adviseContext(null)` 会抛出 `IllegalArgumentException`。

### 2. 同时实现 CallAroundAdvisor + StreamAroundAdvisor

虽然增加了代码量，但保证了流式和非流式两条路径的行为一致性。如果只实现一个接口，另一种调用方式会跳过该 Advisor，造成安全校验或日志记录的缺口。

### 3. 通过 adviseContext 跨 Advisor 共享状态

避免使用 ThreadLocal（Reactor 线程切换会丢失）或静态变量（多会话会串）。`adviseContext` 随请求在链中传递，天然隔离。

### 4. 业务异常体系（ErrorCode 枚举）

以 ErrorCode 枚举为单一事实来源，统一管理错误码、HTTP 状态、用户提示、是否可重试四个维度。分类规则：

| 码段 | 类别 | 示例 |
|------|------|------|
| 1xxx | 输入错误 | 空消息(1001)、超长(1002) |
| 2xxx | 内容安全 | 敏感词拦截(2001) |
| 3xxx | AI 调用 | API超时(3001)、余额不足(3002) |
| 4xxx | 会话记忆 | 记忆读写异常(4001) |
| 9999 | 系统兜底 | 内部异常 |

## 遇到的问题与解决

### 环境与依赖

| 问题 | 原因 | 解决 |
|------|------|------|
| `spring-ai-openai-starter:1.0.0-M7` 拉不到 | M7 未发布到 Maven Central | 降级到 `1.0.0-M6` |
| JDK 8 编译 Java 21 项目失败 | 系统 `java` 指向 1.8.0_451 | 编译/运行时显式指定 `JAVA_HOME="D:/develop/java/JDK/jdk-21"` |
| DashScope API Key 无效 | `spring-ai-alibaba-starter` 默认对接阿里 DashScope，需要开通百炼账号 | 切换为 DeepSeek：使用 `spring-ai-openai-starter` + `base-url: https://api.deepseek.com` |
| 测试环境 Spring 上下文启动失败 | `SpringAiAiInvoke`（启动时调用大模型）在 test profile 下尝试连接真实 API 但没有有效 Key | 给 AI 相关组件（PictureApp、SpringAiAiInvoke、ChatController）加 `@Profile("!test")`，test profile 排除 OpenAiAutoConfiguration |
| `reactor-test` 找不到 | Stream API 测试需要 `StepVerifier` 但未声明依赖 | 在 pom.xml 添加 `io.projectreactor:reactor-test`（test scope） |
| 端口 8231 被占用 | 前次启动的进程未关闭 | 用 `netstat -ano` 查找 PID，`taskkill //F` 终止 |

### Spring AI M6 API 探索

M6 是稳定版落地前的最后一个 Milestone，文档稀缺，API 与 M7+ 差异较大：

| 问题 | 原因 | 解决 |
|------|------|------|
| 编译错误 `AroundAdvisor/AdviseContext 找不到` | M6 的 Advisor 接口叫 `CallAroundAdvisor`/`StreamAroundAdvisor`，上下文容器叫 `AdvisedRequest`/`AdvisedResponse`，与后来 M7 引入的 `AroundAdvisor`/`AdviseContext` 完全不同 | 用 `javap` 反编译 JAR 包确认正确类名和方法签名 |
| `"adviseContext cannot be null"` | M6 的 `AdvisedResponse.Builder` 对 `adviseContext(Map)` 做了非 null 校验 | 构造 AdvisedResponse 时始终显式传入 `new HashMap<>()`，不可省略 |
| `"chatModel cannot be null"` 测试报错 | `AdvisedRequest.Builder` 要求提供 ChatModel 实例 | 单元测试中传入 `mock(ChatModel.class)` 构造 AdvisedRequest |
| AdvisedRequest/AdvisedResponse 不可变 | M6 中这两个类是 Java Record，没有 setter | 修改 adviseContext 必须 `new HashMap<>(old) → put → AdvisedRequest.from(old).adviseContext(newMap).build()` |

### SpringDoc/Knife4j 兼容性

| 问题 | 原因 | 解决 |
|------|------|------|
| Knife4j 文档页 `/api/doc.html` 报 9999 错误 | Spring Boot 3.5.14 底层 Spring 6.2 移除了 `ControllerAdviceBean(Object)` 单参构造函数；Knife4j 4.4.0 内 springdoc 2.3.0 仍调用旧 API → `NoSuchMethodError` | ① 去掉 Knife4j 依赖 ② 显式引入 `springdoc-openapi-starter-webmvc-ui:2.8.13`（该版本适配 Spring 6.2） ③ swagger-ui 路径设为 `/doc.html` 保持体验一致 |
| 初步误判为 `Flux<String>` 兼容问题 | 报错信息被 GlobalExceptionHandler 统一封装为 "9999系统异常"，未暴露根因 | 查看完整堆栈日志（`grep "Caused by"`）定位到 `NoSuchMethodError` |
| Knife4j 4.5.0 仍不兼容 springdoc 2.8.13 | Knife4j 内部调用 `SpringDocConfigProperties.getGroupConfigs()` 返回类型不匹配 | 短期内无法同时使用 Knife4j + Spring Boot 3.5，等 Knife4j 官方适配 |

### Advisor 设计迭代

| 问题 | 原因 | 解决                                                                                                |
|------|------|---------------------------------------------------------------------------------------------------|
| 日志看不到原始用户输入 | LoggingAdvisor（order=30）在 PromptOptimize（order=20）之后执行，`request.userText()` 已被改写 | PromptOptimize 在改写前将原始输入写入 `adviseContext`（key: `originalUserText`），LoggingAdvisor 从中读取           |
| 流式日志只能记耗时，无完整回复内容 | v1 用 `doOnComplete` 只有回调时机，拿不到聚合后的文本 | 引入 `MessageAggregator.aggregateAdvisedResponse(flux, consumer)`，consumer 收到聚合后的完整 AdvisedResponse |
| 不知道 `MessageAggregator` 的存在 | Spring AI M6 文档中对这个工具类提及甚少 | 参考外部代码 + 直接查看 spring-ai-core JAR 中的 `MessageAggregator.class`                                     |
| 流式异常处理中 onErrorResume 顺序错误 | 先写了 `.onErrorResume(Throwable, ...)` 再 `.onErrorResume(AiAgentException, ...)`，导致业务异常被兜底分支吞掉 | 必须先匹配子类再匹配父类：`.onErrorResume(AiAgentException.class, ...).onErrorResume(e -> ...)`                |
| Mockito 测试时 AdvisedResponse 的 response 字段为 null | 未设置 ChatResponse，导致日志 `safeGetText` 抛 NPE | 统一提供 `emptyResponse()` 工具方法，构造带有效 `AssistantMessage("ok")` 的 ChatResponse                         |

## 测试覆盖

24 个单元测试，按 Advisor 分组：

- **ContentGuardAdvisor**: 空消息、null、超长、敏感词、合法放行、order 校验 — 6 个
- **PromptOptimizeAdvisor**: 改写验证、adviseContext 共享、空消息放行、order 校验 — 4 个
- **LoggingAdvisor**: 非流式日志、流式 MessageAggregator 聚合、order 校验 — 3 个
- **ExceptionGuardAdvisor**: 正常放行、业务异常降级、未知异常降级、流式异常两层 onErrorResume — 6 个
- **PictureApp 多轮对话**: 上下文保持、多会话隔离、系统提示词注入 — 3 个
- **集成测试**: 真实 DeepSeek API 调用验证全链路 — 1 个
- **上下文加载**: Spring 容器启动验证 — 1 个
