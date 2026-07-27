# 05 — Advisor 拦截链

Advisor 是 Spring AI 的拦截器机制，类似 Web 的 Filter/Interceptor。每个 Advisor 在 AI 调用前后执行，可以修改 prompt、注入上下文、记录日志、处理异常。

## 执行顺序

```
请求进入
  │
  ▼
┌──────────────────────────────────────┐
│ ① ContentGuardAdvisor     (order=0)  │  输入校验
├──────────────────────────────────────┤
│ ② MessageChatMemoryAdvisor (内置)     │  对话记忆管理
├──────────────────────────────────────┤
│ ③ RagInjectionAdvisor    (order=15)  │  RAG文本注入
├──────────────────────────────────────┤
│ ④ PromptOptimizeAdvisor  (order=20)  │  提示词优化
├──────────────────────────────────────┤
│ ⑤ LoggingAdvisor         (order=30)  │  横切日志
├──────────────────────────────────────┤
│ ⑥ ExceptionGuardAdvisor  (order=MAX) │  异常兜底
└──────────────────────────────────────┘
  │
  ▼
ChatModel.call() → AI 模型
  │
  ▼
响应原路返回 (⑥→⑤→④→③→②→①)
```

## 逐个详解

### ① ContentGuardAdvisor (order=0)

**为什么是第一个？** 如果输入不合法，后续所有处理都是浪费。必须在第一时间拦截。

**校验规则：**

| 校验项 | 规则 | 违例错误码 |
|--------|------|-----------|
| 消息长度 | ≤ 2000 字符 | MESSAGE_TOO_LONG (40101) |
| 敏感词 | 禁止"暴力""色情""政治敏感" | CONTENT_BLOCKED (40200) |
| 图片数量 | ≤ 5 张 | PARAMS_ERROR (40000) |
| 图片大小 | ≤ 10MB/张 | IMAGE_TOO_LARGE (40001) |
| 图片格式 | png/jpeg/jpg/webp/gif | PARAMS_ERROR (40000) |

**已知局限**：关键词过滤仅有字面匹配，"继续我上一个任务"可绕过——无语义审核能力。

**流式处理**：校验失败返回 `Flux.error(new BusinessException(...))`。

### ② MessageChatMemoryAdvisor (内置，Spring AI 提供)

**作用**：
- **调用前**：从 `ChatMemory` 取最近 N 条消息，注入 prompt 作为对话历史
- **调用后**：将本次的用户消息 + AI 回复写入 `ChatMemory`

**重要**：Spring AI 1.0.0 GA 的 MCMA **不支持** `chatMemoryRetrieveSize` 参数！截断由 `RedisChatMemory.get()` 内部通过 Redis LRANGE 实现。

### ③ RagInjectionAdvisor (order=15)

**为什么 order=15？** 必须在 MCMA（内置）之后、PromptOptimize（20）之前。

**作用**：将 RAG 增强后的文本替换用户原始消息。只在 advisor context 中存在 `KEY_RAG_AUGMENTED` 参数时生效（仅 generation 模式设置此参数）。

**关键代码逻辑**：
```java
String ragText = (String) adviseContext.get("KEY_RAG_AUGMENTED");
if (ragText == null) return nextCall(request, chain);  // 非生图模式，跳过

Message augmentedMsg = new UserMessage(ragText, userMsg.getMedia());
// 保留原始 media，只替换文本
AdvisedRequest newReq = AdvisedRequest.from(request)
    .userMessage(augmentedMsg).build();
return nextCall(newReq, chain);
```

### ④ PromptOptimizeAdvisor (order=20)

**作用**：用优化模板包裹用户消息，让 LLM 从模糊输入中提取具体元素。

**模板** (`optimize.st`)：
```
请根据以下用户需求，提取并优化图片生成所需的具体元素：
- 主体（subject）
- 环境（scene）
- 光线（lighting）
- 风格（style）
- 质量（quality）

用户需求：{userInput}
```

**已知问题**：用户发"继续上一个任务"等无头指令时，LLM 会随机猜测主题（模型幻觉）。

### ⑤ LoggingAdvisor (order=30)

**作用**：记录每次 AI 调用的完整信息，便于调试和问题追踪。

**记录内容**：
- 请求时间、chatId、模式
- 原始输入 vs 实际发送的文本（如果不一致说明被改写过）
- Prompt 长度和内容（截断到前 500 字符）
- AI 响应耗时
- 响应文本长度和内容

**流式处理**：使用 `ChatClientMessageAggregator` 等待流聚合完成后再记录。

### ⑥ ExceptionGuardAdvisor (order=MAX)

**作用**：安全网——捕获整个 advisor 链或 AI 调用中抛出的任何异常，转换为友好的错误消息，确保调用者始终收到有效响应而非原始异常。

**异常分级处理**：
- `BusinessException`：记录 warn 日志，消息直接透传给用户
- 其他异常：记录 error 日志（含堆栈），返回泛化提示"系统错误，请稍后重试"

**已知坑**：流式路径的 `.onErrorResume` 顺序错误会导致 BusinessException 被父类 Exception 吞掉。

## Advisor 不可变 Record 的操作规范

`AdvisedRequest` 和 `AdvisedResponse` 是 Java Record，修改 `adviseContext` 必须复制—修改—重建：

```java
// ✅ 正确
Map<String, Object> ctx = new HashMap<>(request.adviseContext());
ctx.put("key", value);
return AdvisedRequest.from(request).adviseContext(ctx).build();

// ❌ 错误 — Record 不可变
request.adviseContext().put("key", value);

// ❌ 错误 — Builder 不接受 null
.adviseContext(null);
```
