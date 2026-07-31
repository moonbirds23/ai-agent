# 04 — 生图 + RAG 流程（mode=image_generation）

这是整个项目最核心、最复杂的链路。一次请求经过：参数校验 → 图片入库 → RAG 上下文构建 → Advisor 链增强 → AI 生图参数提取 → 调用生图 API → 结果入库 → 响应返回。

## 完整数据流

```
用户："帮我把这张图变成吉卜力风格" + 图片base64
     │
     ▼
PictureApp.handleGeneration(request, chatId)
     │
     ├─ ① autoSaveToCacheGallery(request)
     │     └─ 图片 SHA-256 去重 → CACHE 位置入库 → GalleryPicture
     │
     ├─ ② prepareRagContext(request, chatId, "RAG")
     │     │
     │     ├─ [条件] request.message 为空 且 有图片
     │     │     └─ visionAnalysisService.analyze()
     │     │        提取视觉特征(主体/风格/色彩/构图) → 作为检索 query
     │     │        这是"图→图检索"能力
     │     │
     │     └─ ragService.buildContext(ragRequest)
     │           │
     │           ├── Layer 1: 显式参考图解析
     │           │     if (request.referencePictureIds != null)
     │           │       → ExplicitReferenceResolver.resolve(ids)
     │           │         ├─ GalleryService.listByIds() → 加载图库元数据
     │           │         └─ PictureAiProfileService.getByPictureId() → 加载AI画像
     │           │       → 结果入 RagContext.explicitReferences
     │           │
     │           ├── Layer 2: RAG 增强检索
     │           │     if (request.useGalleryRag != false 且有查询文本)
     │           │       → layer2Enhance()
     │           │         │
     │           │         ├─ 2a. buildConversationHistory(chatId)
     │           │         │     └─ ChatMemory.get() → 取最近20条消息
     │           │         │        每条截断到200字符 → 拼接为 history 字符串
     │           │         │
     │           │         ├─ 2b. QueryRewrite
     │           │         │     └─ LLM 将 "吉卜力风格" + 对话历史
     │           │         │        改写为结构化搜索条件:
     │           │         │        { searchQuery:"吉卜力 动画 手绘",
     │           │         │          category:"插画",
     │           │         │          tags:["吉卜力","动画","治愈"],
     │           │         │          styleHints:["吉卜力","手绘","水彩"],
     │           │         │          colorHints:["温暖","柔和"],
     │           │         │          compositionHints:[],
     │           │         │          referenceMode:"style",
     │           │         │          templateHint:"吉卜力" }
     │           │         │
     │           │         ├─ 2c. HybridRetrieve
     │           │         │     └─ PgVectorIndexService.search(query, topK×4, minScore=0.4)
     │           │         │           → pgvector cosine 相似度搜索
     │           │         │        对每个命中结果:
     │           │         │          • 加载 GalleryPicture + PictureAiProfile
     │           │         │          • (可选) 过滤 favoritesOnly
     │           │         │          • 计算 keywordScore (标签匹配+名称包含)
     │           │         │          • 计算 metadataScore (类别+风格+色彩+构图匹配)
     │           │         │
     │           │         └─ 2d. Rerank
     │           │               └─ 加权线性组合:
     │           │                  finalScore = vectorScore × 0.5
     │           │                             + keywordScore × 0.3
     │           │                             + metadataScore × 0.2
     │           │                  排序降序 → 截断到 topK=5
     │           │       → 结果入 RagContext.retrievedReferences
     │           │
     │           ├── Layer 3: 风格模板兜底
     │           │     if (L1和L2都为空)
     │           │       → 优先用 request.styleTemplateCode 精确查找
     │           │       → 否则 StyleTemplateService.match(query) 关键词匹配
     │           │       → 设置 RagContext.styleTemplate
     │           │
     │           └── ContextPacker
     │                 └─ 格式化三层参考为文本
     │                    • referenceMode 裁剪 (overall/style/color/composition)
     │                    • 截断到 maxContextChars 字符
     │                    • 模板文本永不被截断
     │
     ├─ ③ buildGenerationUserSpec(request, ragPrepared)
     │     └─ 构造 UserMessage:
     │        text = ragPrepared.enhancedMessage (RAG渲染后的文本)
     │        media = 用户上传的图库图片 (供视觉参考)
     │             + ragPrepared 中的参考图 Media (最多3张)
     │
     ├─ ④ ChatClient.prompt()
     │     ├─ .user(spec → text + media)
     │     ├─ .advisors(a → a
     │     │     .param("chatId", chatId)
     │     │     .param("ragAugmentedText", ragPrepared.enhancedMessage))
     │     └─ .call().entity(outputConverter)
     │           │
     │           ▼
     │      Advisor 链执行:
     │        ContentGuard(0) → 校验消息+图片
     │        MCMA(内置) → 注入历史(retrieveSize=50) + 存储原文(非RAG文本)
     │        RagInjection(15) → 用 RAG 文本替换用户消息内容
     │        PromptOptimize(20) → LLM 优化提示词
     │        Logging(30) → 记录
     │        ExceptionGuard(MAX) → 兜底
     │           │
     │           ▼
     │      ChatModel.call() → glm-4-flash
     │        请求: system(生图助手角色) + user(RAG文本 + 参考图 + 历史)
     │        响应: ImageAgentResponse {
     │          type: "image_ready",
     │          message: "好的，我根据吉卜力风格为你生成...",
     │          imagePrompt: "A hand-drawn style illustration in Ghibli...",
     │          style: "吉卜力",
     │          dimensions: "16:9"
     │        }
     │
     ├─ ⑤ imageGenerationService.generate(imagePrompt, style, dimensions)
     │     │
     │     ▼
     │  ZhipuImageGenerationService
     │    POST https://open.bigmodel.cn/api/paas/v4/images/generations
     │    Body: { model:"cogview-4",
     │            prompt:"A hand-drawn style illustration in Ghibli...，吉卜力风格",
     │            size:"1344x768" }
     │    返回: { data:[{url:"https://..."}] }
     │    → ImageGenerationResult { imageUrl, imageBase64(下载后转) }
     │
     ├─ ⑥ (可选) saveGeneratedToGallery(request, result)
     │     └─ 如果 request.saveGeneratedToGallery = true
     │       → GalleryService.upload() 或 importFromUrl()
     │       → 生成的图片入库，下次可被 RAG 检索
     │
     └─ ⑦ ChatResponseVO.imageGenerated(
           chatId, imageUrl, imagePrompt, style, dimensions, message, ragDebugInfo
         )
          返回: {
            type: "image_generated",
            imageUrl: "...",
            imagePrompt: "...",
            style: "吉卜力",
            message: "好的，我根据吉卜力风格...",
            ragDebugInfo: { explicit:[...], retrieved:[...], template:{...} }
          }
```

## 关键设计决策

### 1. RagInjectionAdvisor 的 order=15

**必须在 MessageChatMemoryAdvisor 之后执行**。MCMA 将用户消息存入 ChatMemory，如果 RagInjection 在前，膨胀的 RAG 文本（几百上千字）会被存入记忆，后续每轮对话都带着这个大文本，token 消耗爆炸。

当前设计：MCMA 存的是用户原始消息（如"吉卜力风格"），RAG 文本只在本次 AI 调用中生效。

### 2. 生成模式 ChatMemory 取 50 条

生成模式比讨论模式需要更多上下文（用户可能在多轮中逐步调整需求），所以 `retrieve_size=50`。

### 3. 图→图检索

用户上传参考图但没写文字时，系统自动调视觉模型提取特征，用特征文本做向量检索。这让"以图搜图"成为可能。

### 4. 生成图自动入库

`saveGeneratedToGallery` 为 true 时，生成的图片会自动存入图库。好处：
- 积累图库素材，丰富 RAG 检索库
- 下次生图时可作为参考图引用
- 自动触发 AI 画像分析 + 向量索引
