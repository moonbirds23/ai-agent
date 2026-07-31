# RAG 全链路详解

> 从用户输入到最终生图输出，逐层拆解架构设计和实现技术。

## 总览

```
用户发送消息
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│  Controller 层                                           │
│  ChatController.chat() → 透传，包装 BaseResponse          │
│  技术：Spring MVC @PostMapping, @Valid, ResultUtils       │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  App 层                                                  │
│  PictureApp.doChat() → 三模式路由 → handleGeneration()     │
│  技术：resolveMode() 状态机，switch 表达式                  │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  Advisor 拦截链（顺序执行）                                │
│                                                          │
│  ① ContentGuard        (order=0)   输入安全校验           │
│  ② MessageChatMemory   (built-in)  注入历史会话            │
│  ③ ★ QuestionAnswer   (order=10)  RAG检索+上下文注入 ★    │
│  ④ PromptOptimize      (order=20)  Prompt改写优化          │
│  ⑤ Logging             (order=30)  全量日志                │
│  ⑥ ExceptionGuard      (order=MAX) 异常兜底                │
│                                                          │
│  技术：Spring AI CallAroundAdvisor 链                      │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  LLM 调用层                                              │
│  DeepSeek ChatModel → 结构化输出 ImageAgentResponse        │
│  技术：BeanOutputConverter, @JsonClassDescription          │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  Image 层                                                │
│  ImageGenerationService.generate() → CogView-4 生图       │
│  技术：智谱 API，尺寸归一化                                 │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│  返回 ChatResponseVO（含 imageUrl, imagePrompt 等）        │
└─────────────────────────────────────────────────────────┘
```

---

## 逐层详解

### 第 1 层：Controller — 请求入口

```
POST /api/chat
Content-Type: application/json

{
  "message": "帮我生成温暖的日式卧室",
  "chatId": "abc123",
  "mode": "image_generation"
}
```

**做什么：** 只做透传 + 响应包装，不做任何业务判断。

```java
// ChatController.java — 当前代码
@PostMapping
public BaseResponse<ChatResponseVO> chat(@Valid @RequestBody ChatRequest request) {
    String chatId = ...; // UUID 或前端传入
    ChatResponseVO data = pictureApp.doChat(request, chatId);
    return ResultUtils.success(data);  // → {"code":0, "data":{...}, "message":"ok"}
}
```

| 技术 | 说明 |
|------|------|
| `@Valid` + Jakarta Validation | `ChatRequest` 中 `@NotBlank message` |
| `ResultUtils.success()` | 统一响应包装，对齐 Picture-Backend |
| Spring MVC | 同步请求/响应模型 |

**Controller 不做的事：** 不解析 mode、不调用 AI、不处理异常（由 ExceptionGuard 兜底）。

---

### 第 2 层：PictureApp — 三模式路由

```java
// PictureApp.java — 修订后（加入 RAG）
public ChatResponseVO doChat(ChatRequest request, String chatId) {
    return switch (resolveMode(request)) {
        case MODE_CHAT              → handleDiscussion(request, chatId);
        case MODE_IMAGE_ANALYSIS    → handleImageAnalysis(request, chatId);
        case MODE_IMAGE_GENERATION  → handleGeneration(request, chatId);  // ★ 走这条
        default → throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的对话模式");
    };
}
```

**做什么：** 根据 `mode` 字段分流到三条不同的处理链路。生图模式进入 `handleGeneration()`。

```java
// ★ 生图模式 — 含 RAG 增强
private ChatResponseVO handleGeneration(ChatRequest request, String chatId) {
    String msg = request.message();
    if (msg == null || msg.isBlank()) {
        msg = "基于以上对话内容，请生成最终的图片生成参数";
    }

    ImageAgentResponse aiResp = chatClient.prompt()
            .user(msg)
            .advisors(spec -> spec
                    .param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 50)
                    .param("chatId", chatId))
            .call()
            .entity(outputConverter);  // → 结构化输出 ImageAgentResponse

    ImageGenerationResult genResult = imageGenerationService.generate(
            aiResp.imagePrompt(), aiResp.style(), aiResp.dimensions());

    return ChatResponseVO.imageGenerated(chatId, genResult.imageUrl(), ...);
}
```

| 技术 | 说明 |
|------|------|
| `resolveMode()` | 状态机：`generationMode` 旧字段 + `mode` 新字段兼容 |
| `BeanOutputConverter<ImageAgentResponse>` | 约束 DeepSeek 输出为 JSON Schema，自动反序列化 |
| `ImageGenerationService` | 接口注入，当前实现 `ZhipuImageGenerationService` |

**路由决策点：** 生图模式才走 RAG（QuestionAnswerAdvisor 只在生图 ChatClient 的 advisor 链中配置），讨论模式不触发检索。

---

### 第 3 层：Advisor 拦截链 — 核心增强

这是 RAG 最关键的层。每个 Advisor 包裹着"调用 LLM"这个动作，在调用前后插入逻辑。

#### 执行顺序和职责

```
请求进入
  │
  ▼
┌──────────────────────────────────────────────────────┐
│ ① ContentGuardAdvisor (order=0)                      │
│    前置：校验 message 非空、长度 ≤2000                  │
│    前置：校验图片数量/大小/格式                          │
│    不合法 → throw BusinessException，短路后续所有节点    │
│    技术：Text/Image validation，ThrowUtils             │
└──────────────────────────┬───────────────────────────┘
                           │ 通过
                           ▼
┌──────────────────────────────────────────────────────┐
│ ② MessageChatMemoryAdvisor (Spring AI built-in)      │
│    前置：从 ChatMemory 注入历史消息到 request           │
│    后置：把本轮 user + assistant 消息写入 ChatMemory    │
│    技术：RedisChatMemory（我们实现的 Redis 版）          │
│    参数：conversationId, retrieveSize=50              │
└──────────────────────────┬───────────────────────────┘
                           │ request 已含历史上下文
                           ▼
┌──────────────────────────────────────────────────────┐
│ ③ ★ QuestionAnswerAdvisor (order=10) ★              │
│                                                      │
│  ┌─ 3a. 提取用户查询                                   │
│  │   从 request.userText() 取原始消息                  │
│  │   "帮我生成温暖的日式卧室"                           │
│  └───────────────────────────────────────────────────│
│  ┌─ 3b. [可选] QueryRewriter 改写查询                  │
│  │   调用 DeepSeek 改写为更精确的检索描述               │
│  │   → "暖色调日式风格卧室，柔光，榻榻米，原木格栅..."    │
│  │   技术：ChatClient 额外调用（轻量，不走 advisor 链）   │
│  └───────────────────────────────────────────────────│
│  ┌─ 3c. Embedding + 向量检索                          │
│  │   embeddingModel.embed(query) → float[]            │
│  │   vectorStore.similaritySearch(query, topK=5)      │
│  │   技术：EmbeddingModel (OpenAI 兼容 API)             │
│  │         SimpleVectorStore (内存余弦相似度)           │
│  └───────────────────────────────────────────────────│
│  ┌─ 3d. 注入检索结果到 Prompt                          │
│  │   匹配到的 Document 文本注入到 user message 上下文    │
│  │   [原始消息] + [检索到的参考图片描述]                  │
│  │   技术：Spring AI 自动拼接，使用默认模板              │
│  └───────────────────────────────────────────────────│
└──────────────────────────┬───────────────────────────┘
                           │ augmented request
                           ▼
┌──────────────────────────────────────────────────────┐
│ ④ PromptOptimizeAdvisor (order=20)                   │
│    前置：改写 userText，优化生图 Prompt                  │
│    不负责任何 RAG 逻辑（检索结果已在上一步注入）          │
│    技术：PromptTemplate (StringTemplate) + ChatClient  │
└──────────────────────────┬───────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────┐
│ ⑤ LoggingAdvisor (order=30)                          │
│    前置：记录 request（原始输入 + 改写 Prompt）          │
│    后置：记录 response（回复内容、字符数、耗时）          │
│    流式：MessageAggregator 聚合所有 token              │
│    技术：Slf4j + System.currentTimeMillis()           │
└──────────────────────────┬───────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────┐
│ ⑥ ExceptionGuardAdvisor (order=MAX)                  │
│    异常兜底：catch BusinessException → 友好回复        │
│    非流式：try-catch                                 │
│    流式：.onErrorResume(BusinessException.class, ...) │
└──────────────────────────────────────────────────────┘
                           │
                           ▼
                      调用 LLM
```

#### ★ 重点：QuestionAnswerAdvisor 内部机制

这是 Spring AI 官方提供的 advisor，来自 `spring-ai-advisors-vector-store`（或已内置在 M6 的 spring-ai-spring-boot-starter 中）。

```java
// 我们的配置代码（KnowledgeConfig.java 中创建 Bean）
@Bean
QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
    return new QuestionAnswerAdvisor(vectorStore);
}

// PictureApp 中注入到生图模式的 advisor 链
this.chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        new ContentGuardAdvisor(),
        new MessageChatMemoryAdvisor(chatMemory),
        questionAnswerAdvisor,           // ★ RAG 检索注入
        new PromptOptimizeAdvisor(promptTemplate),
        new LoggingAdvisor(),
        new ExceptionGuardAdvisor()
    )
    .build();
```

**内部执行流程（我们不需要写）：**

```
QuestionAnswerAdvisor.aroundCall(request, chain)
  │
  ├─ 1. 从 request 提取 userText
  │     "帮我生成温暖的日式卧室"
  │
  ├─ 2. vectorStore.similaritySearch(
  │         SearchRequest.defaults()
  │             .withQuery("帮我生成温暖的日式卧室")
  │             .withTopK(5)
  │             .withSimilarityThreshold(0.5)
  │     )
  │     内部：embeddingModel.embed(queryText)
  │           → [0.023, -0.451, 0.891, ...]
  │           → SimpleVectorStore 内存余弦遍历
  │           → 返回 topK 个 Document
  │
  ├─ 3. 返回结果示例：
  │     Document[
  │       id="k1",
  │       text="暖黄光卧室，棉麻床品，原木家具，飘窗白纱帘…",
  │       metadata={title:"日式主卧参考", tags:["日式","暖色"], score:0.87}
  │     ]
  │     Document[
  │       id="k2",
  │       text="日式茶室，竹帘光影，榻榻米，陶器摆设…",
  │       metadata={title:"茶室灵感", tags:["日式","茶室"], score:0.82}
  │     ]
  │
  ├─ 4. 注入到 Prompt（默认 user message 模板）：
  │     """
  │     Context information is below:
  │     暖黄光卧室，棉麻床品，原木家具，飘窗白纱帘…
  │     日式茶室，竹帘光影，榻榻米，陶器摆设…
  │
  │     Given the context information, answer the query:
  │     帮我生成温暖的日式卧室
  │     """
  │
  └─ 5. chain.next(augmentedRequest) → 继续后续 advisor → LLM
```

| 配置项 | V1 值 | 说明 |
|--------|-------|------|
| `topK` | 5 | 最多返回 5 个匹配文档 |
| `similarityThreshold` | 0.5 | 余弦相似度低于 0.5 的不返回 |
| 检索模式 | 纯向量 | 不涉及 BM25 / 关键词 |

---

### 第 4 层：LLM 调用 — DeepSeek 结构化输出

```
Augmented Prompt（QuestionAnswerAdvisor 注入后）:
─────────────────────────────────────────────
[System] 你是云图库 AI 图片生成助手。请输出以下 JSON 格式：
         {"message":"...", "imagePrompt":"...", "style":"...", "dimensions":"..."}

[Context] 以下是用户知识库中相似风格的图片描述：
         1. 暖黄光卧室，棉麻床品，原木家具，飘窗白纱帘…
         2. 日式茶室，竹帘光影，榻榻米，陶器摆设…

[User] 帮我生成温暖的日式卧室
─────────────────────────────────────────────
```

```java
// BeanOutputConverter 约束输出格式
// DeepSeek 返回：
{
  "message": "为您生成了一间温暖的日式卧室",
  "imagePrompt": "日式风格卧室，暖黄柔光，榻榻米，低矮木质床架，白墙原木格栅，飘窗白纱帘，棉麻床品，陶器花瓶，温馨舒适",
  "style": "日式",
  "dimensions": "1024x1024",
  "revisedPrompt": "温暖的日式风格卧室，柔和暖光..."
}

// 自动反序列化为 ImageAgentResponse
ImageAgentResponse aiResp = chatClient.call().entity(outputConverter);
```

| 技术 | 说明 |
|------|------|
| `BeanOutputConverter` | 自动生成 JSON Schema 注入 system prompt，约束 LLM 输出格式 |
| DeepSeek Chat API | 通过 Spring AI OpenAI Starter，`OpenAiApi` 指向 DeepSeek endpoint |
| `@JsonClassDescription` | `ImageAgentResponse` 上的注解，为 Schema 生成字段描述 |

**RAG 的价值在这里体现：** DeepSeek 看到的不只是用户的一句话，而是"用户需求 + 知识库中相似风格的详细视觉描述"，生成的 `imagePrompt` 更具体、更贴近用户的审美积累。

---

### 第 5 层：Image 层 — 智谱 CogView-4 生图

```java
// ZhipuImageGenerationService.generate()
public ImageGenerationResult generate(String prompt, String style, String dimensions) {
    // 1. 尺寸归一化（CogView-4 支持的尺寸有限）
    String normalizedSize = normalizeSize(dimensions);  // "1024x1024"

    // 2. 调用智谱 CogView-4 API
    // POST https://open.bigmodel.cn/api/paas/v4/images/generations
    // Body: { "model": "cogview-4", "prompt": "...", "size": "1024x1024" }

    // 3. 返回临时图片 URL
    return new ImageGenerationResult(imageUrl, imageBase64);
}
```

| 技术 | 说明 |
|------|------|
| 智谱 CogView-4 API | 文生图模型 |
| 尺寸归一化 | 用户尺寸映射到 CogView-4 支持的枚举值 |
| 临时 URL | 不持久化，由 ImageDownloadService 代理下载 |

---

### 第 6 层：返回响应

```java
// ChatResponseVO.imageGenerated()
public static ChatResponseVO imageGenerated(
        String chatId, String imageUrl, String imageBase64,
        String message, String imagePrompt, String style,
        String dimensions, String revisedPrompt) {
    return new ChatResponseVO(
        chatId,
        "image_generated",   // type
        message,             // DeepSeek 的友好回复
        imageUrl,            // 生图结果 URL
        imageBase64,         // 生图结果 base64
        imagePrompt,         // 本次使用的 Prompt（前端可展开查看）
        style,
        dimensions,
        revisedPrompt,       // DeepSeek 润色后的 Prompt
        null                 // 分析模式才有的字段
    );
}
```

```
HTTP 200
{
  "code": 0,
  "message": "ok",
  "data": {
    "chatId": "abc123",
    "type": "image_generated",
    "message": "为您生成了一间温暖的日式卧室",
    "imageUrl": "https://open.bigmodel.cn/...",
    "imagePrompt": "日式风格卧室，暖黄柔光，榻榻米...",
    "style": "日式",
    "dimensions": "1024x1024",
    "revisedPrompt": "温暖的日式风格卧室，柔和暖光..."
  }
}
```

前端拿到后：展示图片 + 下载按钮 + 展开查看 Prompt + "加入知识库"按钮。

---

## 完整技术栈映射

```
┌────────────────────┬──────────────────────────────────┐
│ 层                  │ 技术                              │
├────────────────────┼──────────────────────────────────┤
│ Controller          │ Spring MVC, @Valid, ResultUtils  │
│ App 路由            │ switch 表达式, resolveMode()      │
│ Advisor 链          │ Spring AI CallAroundAdvisor       │
│   ContentGuard      │ ThrowUtils, 字符串/图片校验        │
│   ChatMemory        │ RedisChatMemory (Redis + Jackson) │
│   ★ QuestionAnswer  │ VectorStore.similaritySearch()   │
│   PromptOptimize    │ PromptTemplate (StringTemplate)   │
│   Logging           │ Slf4j + MessageAggregator         │
│   ExceptionGuard    │ try-catch / onErrorResume         │
│ Embedding           │ EmbeddingModel (OpenAI 兼容 API)  │
│ Vector Store        │ SimpleVectorStore (内存余弦)      │
│ LLM (文本理解)       │ DeepSeek via OpenAI-compatible API│
│ LLM (结构化输出)     │ BeanOutputConverter<ImageAgent..> │
│ LLM (查询改写)       │ ChatClient 额外调用 (QueryRewriter)│
│ Image Generation    │ 智谱 CogView-4 API                │
│ Vision Analysis     │ 智谱 GLM-4.5V API                 │
│ 文件存储             │ LocalAssetStorage (本地文件系统)   │
│ 元数据               │ SQLite (sqlite-jdbc)              │
│ 响应包装             │ BaseResponse + ResultUtils        │
└────────────────────┴──────────────────────────────────┘
```

---

## 知识入库链路（另一条独立的线）

这条链路不经过 Advisor 链，是独立的 `KnowledgeService.add()` 调用：

```
用户在前端点 "加入知识库"
  │
  ▼
POST /api/knowledge/add
  {
    "imageUrl": "...",       // 刚生成的图片 URL
    "title": "日式卧室参考",
    "description": "暖黄光日式卧室...",  // Vision 分析结果或用户手动填
    "tags": ["日式", "暖色", "卧室"]
  }
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│  KnowledgeService.add()                                  │
│                                                          │
│  ① RemoteImageDownloadService.download(imageUrl)         │
│     → InputStream（下载远程图片到本地）                    │
│                                                          │
│  ② assetStorage.store(inputStream, fileName, contentType) │
│     → StoredAsset{key: "default/abc123.webp"}            │
│     → 文件写入 ./kb-data/assets/default/abc123.webp       │
│                                                          │
│  ③ [如果用户没传 description]                              │
│     visionService.analyze(imageUrl)                      │
│     → VisionAnalysisResult{title, description, tags}     │
│                                                          │
│  ④ KnowledgeAsset asset = new KnowledgeAsset(             │
│         id=UUID, userId="default",                        │
│         storageUrl="./kb-data/assets/...",                │
│         title, description, tags,                         │
│         sourceType=AI_GENERATED,                          │
│         visibility=PRIVATE                                │
│     )                                                    │
│                                                          │
│  ⑤ metadataRepo.save(asset)  → SQLite INSERT             │
│                                                          │
│  ⑥ Document doc = Document.builder()                     │
│         .id(asset.id)                                    │
│         .text(asset.description)  ← 用于 embedding        │
│         .metadata("title", title, "tags", tags, ...)     │
│         .build()                                         │
│     vectorStore.add(List.of(doc))                         │
│     → EmbeddingModel.embed(description) → 向量            │
│     → SimpleVectorStore 内存写入                          │
│                                                          │
│  ⑦ 返回 KnowledgeAsset                                    │
└─────────────────────────────────────────────────────────┘
```

| 步骤 | 技术 | 我们写的 | Spring AI 的 |
|------|------|----------|-------------|
| ① 下载图片 | `RemoteImageDownloadService` | ✓ | |
| ② 存文件 | `LocalAssetStorage` | ✓ | |
| ③ Vision 分析 | `ZhipuVisionAnalysisService` | ✓ | |
| ④ 构建 domain 对象 | `KnowledgeAsset` record | ✓ | |
| ⑤ 存元数据 | `SqliteKnowledgeRepo` (JDBC) | ✓ | |
| ⑥ embedding + 入库 | `VectorStore.add()` | | ✓ |

---

## 两个关键数据流对比

### KnowledgeAsset（我们管的） vs Document（Spring AI 管的）

```
┌──────────────────────────────────────────┐
│ KnowledgeAsset（业务域 — SQLite）          │
│                                           │
│ id: "abc123"                              │
│ userId: "default"                         │
│ type: IMAGE                               │
│ storageUrl: "./kb-data/.../abc123.webp"   │
│ thumbnailUrl: "..."                       │
│ title: "日式卧室参考"                       │
│ description: "暖黄光日式卧室，棉麻床品…"     │
│ tags: ["日式", "暖色", "卧室"]             │
│ sourceType: AI_GENERATED                  │
│ visibility: PRIVATE                       │
│ metadata: {prompt:"...", style:"日式"}     │
└──────────────┬───────────────────────────┘
               │ 通过 id 关联
               ▼
┌──────────────────────────────────────────┐
│ Document（向量域 — SimpleVectorStore）     │
│                                           │
│ id: "abc123"          ← 与上面相同 id     │
│ text: "暖黄光日式卧室，棉麻床品…"           │ ← embedding 的输入
│ embedding: [0.023, -0.451, ...]           │ ← 768 维 float[]
│ metadata: {                               │
│   "assetId": "abc123",                    │
│   "title": "日式卧室参考",                  │
│   "tags": "日式,暖色,卧室",                 │
│   "style": "日式"                          │
│ }                                         │
└──────────────────────────────────────────┘
```

**为什么分两层：**
- `KnowledgeAsset` 管"图片在哪、谁拥有、什么来源" — 业务语义
- `Document` 管"这段文本的向量是什么、和查询有多相似" — 检索语义
- 查询时从 `Document.metadata` 中拿 `assetId` → 回查 SQLite 拿完整业务对象
- 迁移时 SQLite → MySQL 只影响 `KnowledgeAsset`，`Document` 层不变

---

## 搜索链路（生图模式内的 RAG）

```
用户在生图模式下输入: "帮我生成温暖的日式卧室"
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│  QuestionAnswerAdvisor.aroundCall()                       │
│                                                           │
│  ① 提取查询文本                                            │
│     queryText = request.userText()                        │
│     → "帮我生成温暖的日式卧室"                               │
│                                                           │
│  ② [可选] QueryRewriter.rewrite(queryText)                 │
│     → ChatClient.prompt().user("把这段话改写为更适合        │
│        语义检索的画面描述...").call().content()             │
│     → "暖色调日式风格卧室，柔光，榻榻米，原木格栅..."         │
│     技术：一次额外的 DeepSeek 调用，不走 Advisor 链          │
│                                                           │
│  ③ EmbeddingModel.embed(finalQuery)                       │
│     → POST https://api.deepseek.com/v1/embeddings          │
│     → model: text-embedding-3-small (或其他)               │
│     → 返回 1536 维 float[]                                 │
│     技术：OpenAI 兼容 Embedding API                         │
│                                                           │
│  ④ SimpleVectorStore.similaritySearch()                    │
│     → 内存遍历所有 Document                                │
│     → 对每个 doc.embedding 算余弦相似度                     │
│        cos_sim = dot(query_vec, doc_vec)                   │
│                  / (||query_vec|| * ||doc_vec||)           │
│     → 排序，取 topK=5                                      │
│     → 过滤 similarityThreshold < 0.5 的结果                │
│     技术：纯 Java 数学运算，float[] 点积                     │
│                                                           │
│  ⑤ 返回匹配结果                                            │
│     [Document{id:"k1", score:0.87, text:"暖黄光卧室…"},    │
│      Document{id:"k2", score:0.82, text:"日式茶室…"},      │
│      Document{id:"k3", score:0.71, text:"原木家居…"}]      │
│     (k4, k5 低于 0.5 被过滤)                                │
│                                                           │
│  ⑥ 注入到 user message                                    │
│     augmentedText = DEFAULT_USER_TEXT_ADVISE +             │
│         "Context:\n" + doc1.text + "\n" + doc2.text +     │
│         "\nAnswer: " + originalQuery                      │
│                                                           │
│  ⑦ chain.next(augmentedRequest)                           │
│     → 进入 PromptOptimizeAdvisor                           │
│     → 进入 DeepSeek                                       │
│                                                           │
│  ⑧ DeepSeek 收到的完整上下文：                              │
│     ┌─────────────────────────────────────────────────┐   │
│     │ System: 你是云图库 AI 图片生成助手。               │   │
│     │         输出格式：{"message":..., "imagePrompt":..}│   │
│     │                                                  │   │
│     │ Context (from Knowledge Base):                   │   │
│     │   暖黄光卧室，棉麻床品，原木家具，飘窗白纱帘…       │   │
│     │   日式茶室，竹帘光影，榻榻米，陶器摆设…            │   │
│     │   原木家居风格，暖色调，简约设计…                   │   │
│     │                                                  │   │
│     │ User: 帮我生成温暖的日式卧室                        │   │
│     └─────────────────────────────────────────────────┘   │
│                                                           │
│  ⑨ DeepSeek 响应 → BeanOutputConverter → ImageAgentResponse│
│     imagePrompt 中自然地融合了参考图片的风格元素             │
└──────────────────────────────────────────────────────────┘
```

---

## 端到端延迟估算

| 步骤 | 技术 | 预估延迟 |
|------|------|----------|
| ContentGuard | 纯 CPU 字符串/数字校验 | < 1ms |
| ChatMemory 注入 | Redis GET | ~5ms |
| ★ Embedding (query) | DeepSeek Embedding API | ~200ms |
| ★ 向量搜索 | 内存余弦遍历（百级） | < 5ms |
| ★ Embedding (不开启 QueryRewriter 时) | 同上 | 同上 |
| ★ QueryRewriter（可选） | DeepSeek Chat API 额外调用 | ~2s |
| PromptOptimize | DeepSeek Chat API 调用 | ~3s |
| LLM 主调用 (DeepSeek) | DeepSeek Chat API | ~5s |
| CogView-4 生图 | 智谱 Image API | ~8s |
| **总计（不含 QueryRewriter）** | | **~13s** |
| **总计（含 QueryRewriter）** | | **~15s** |

**V1 建议：** 先不开 QueryRewriter，用原始 query 直接搜，节省 ~2s 和一次 API 费用。

---

## 当前代码需要改什么（从现有到 RAG）

| 文件 | 改动 |
|------|------|
| `pom.xml` | + `spring-boot-starter-data-redis`（已有）, + sqlite-jdbc, + EmbeddingModel 相关依赖 |
| `application.yml` | + embedding model 配置（OpenAI 兼容 endpoint） |
| `PictureApp.java` | 生图模式的 advisor 链中插入 `questionAnswerAdvisor` |
| 新增 `knowledge/` 包 | 约 12 个文件（见设计方案文件清单） |
| `ChatController.java` | 新增 `POST /api/knowledge/add`（入库）, `POST /api/knowledge/search`（可选） |
| 前端 `index.html` | "加入知识库"按钮 + 确认弹窗 |

**已有组件完全不动：** `ContentGuardAdvisor`, `LoggingAdvisor`, `ExceptionGuardAdvisor`, `PromptOptimizeAdvisor`, `RedisChatMemory`, `VisionAnalysisService`, `ImageGenerationService`, `RemoteImageDownloadService`。
