# Advisor 拦截链

## 链的装配位置

Advisor 链在 ChatServiceImpl 构造函数中硬编码为 ChatClient 的 defaultAdvisors：

```java
this.chatClient = ChatClient.builder(openAiChatModel)
    .defaultSystem(systemPrompt)
    .defaultAdvisors(
        new ContentGuardAdvisor(),                               // order=0
        MessageChatMemoryAdvisor.builder(chatMemory).build(),    // 内置, Spring AI
        new RagInjectionAdvisor(),                               // order=15
        new PromptOptimizeAdvisor(promptTemplate),               // order=20
        new LoggingAdvisor(),                                    // order=30
        new ExceptionGuardAdvisor()                              // order=MAX
    )
    .build();
```

## 执行顺序与职责

Advisor 按 `getOrder()` 升序执行。每个 Advisor 同时实现 `CallAdvisor`（非流式）和 `StreamAdvisor`（流式）接口。

| Order | Advisor | 类型 | 职责 |
|-------|---------|------|------|
| 0 | ContentGuard | 自定义 | 输入校验：消息非空、长度≤2000、关键词过滤、图片数量≤5、大小≤10MB |
| 内置 | MessageChatMemoryAdvisor | Spring AI | before(): 从 ChatMemory 取历史消息注入 Prompt；after(): 存当前消息到 ChatMemory |
| 15 | RagInjection | 自定义 | 从 adviseContext 取 RAG 增强文本，替换 UserMessage.text（Media 保留） |
| 20 | PromptOptimize | 自定义 | 模板渲染优化 Prompt，保存原始输入到 adviseContext |
| 30 | Logging | 自定义 | 记录请求/响应摘要日志 |
| MAX | ExceptionGuard | 自定义 | 兜底异常：BusinessException → 友好文案，Exception → 系统错误 |

**Phase 1 关键修复：**

- **ExceptionGuard order**: MAX_VALUE → MIN_VALUE，使其成为链中最外层，兜住所有 Advisor 的 BusinessException
- **RagInjection/PromptOptimize 替换逻辑**: 从"遍历所有 UserMessage 全部替换"改为"从后向前遍历，只替换最后一个（当前轮），break 跳出"。防止 MCMA 注入的历史消息被覆盖
- **RagInjectionAdvisor 新增静态方法**: `applyAugmentation(spec, text)` 封装字符串常量 KEY_RAG_AUGMENTED，消除散落常量

## RagInjectionAdvisor 的时序设计（关键）

这是整个 RAG 链路最精巧的设计点：

```
order=0:   ContentGuard     → 校验通过
order=内置: MCMA             → before(): 取历史消息注入 Prompt
                             →   【此时 UserMessage.text = "冬日雪景"（原始）】
                             → after(): 把 UserMessage 存入 ChatMemory
                             →   【★ 存的也是 "冬日雪景"（原始消息）】
order=15:  RagInjection     → 从 adviseContext 取 ragAugmentedText
                             → 替换 UserMessage.text = "用户需求：冬日雪景\n参考图1：...\n..."
                             → 【★ Prompt 变成增强版，但 ChatMemory 已经存完】
order=20:  PromptOptimize   → 进一步优化增强后的 Prompt
order=30:  Logging          → 记录日志（看到的是增强后的 Prompt）
order=MAX: ExceptionGuard   → try/catch 兜底
```

**为什么这样设计**：RAG 增强文本可能很长（2500 字符），如果被存入 ChatMemory，下一轮对话会带着上一轮的增强文本，导致记忆膨胀和语义污染。通过 order=15 在 MCMA 之后注入，MCMA 存的永远是干净的原始消息。

**Phase 3 对接**: RagInjectionAdvisor.applyAugmentation() 替代散落字符串常量。调用方编译期即可发现漏传参数。

## Advisor 不可变 Record 约束

Spring AI 1.0.0 GA 的 `ChatClientRequest` 和 `ChatClientResponse` 是不可变 Record。修改 adviseContext 需要用"复制→修改→重建"模式：

```java
Map<String, Object> ctx = new HashMap<>(request.adviseContext());
ctx.put("key", value);
return AdvisedRequest.from(request).adviseContext(ctx).build();
```

直接修改会抛 UnsupportedOperationException。

## Advisor 链的扩展点

如果想在不同模式下用不同的 Advisor 链：

```java
// 讨论模式 —— 不含 RagInjection
List<CallAdvisor> discussionChain = List.of(
    new ContentGuardAdvisor(),
    MessageChatMemoryAdvisor.builder(chatMemory).build(),
    new PromptOptimizeAdvisor(pt),
    new LoggingAdvisor(),
    new ExceptionGuardAdvisor()
);

// 生成模式 —— 含 RagInjection
List<CallAdvisor> generationChain = List.of(
    new ContentGuardAdvisor(),
    MessageChatMemoryAdvisor.builder(chatMemory).build(),
    new RagInjectionAdvisor(),       // ← 多了这个
    new PromptOptimizeAdvisor(pt),
    new LoggingAdvisor(),
    new ExceptionGuardAdvisor()
);
```

当前实现用一个全局 ChatClient 实例 + 通过 adviseContext param 控制 RagInjection 是否激活（讨论模式不传 KEY_RAG_AUGMENTED → RagInjection 直接透传）。
