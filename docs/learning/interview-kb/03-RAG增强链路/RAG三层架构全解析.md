# RAG 三层架构全解析

## 什么是 RAG

RAG（Retrieval-Augmented Generation）= 检索增强生成。在用户请求生图时，从图库中检索与用户需求相似的已有图片作为参考，辅助 LLM 生成更精准的 `imagePrompt`。

## 三层架构总览

```
用户需求: "帮我生成一张故宫雪景图"

┌─ Layer 1: 显式参考图（Explicit References）────────────────────┐
│  用户在前端手动勾选 1~3 张图库图片                                │
│  → ExplicitReferenceResolver.resolve(ids)                       │
│     → GalleryService.listByIds(ids)                             │
│     → PictureAiProfileService.listByPictureIds(ids)             │
│  优先级: ★★★ 最高（用户明确指定）                                │
│  短路: 始终执行（只要 referencePictureIds 不为空）               │
└────────────────────────────────────────────────────────────────┘
                          ↓ 后合并
┌─ Layer 2: RAG 增强检索（Gallery RAG Retrieval）─────────────────┐
│  AI 从图库中自动搜索风格/主题相似的图片                           │
│  触发条件: useGalleryRag != false && message 非空               │
│                                                                 │
│  2a. Query Rewrite（LLM 改写查询）                               │
│      → RagQueryRewriteService.rewrite(query, history)           │
│      → LLM 输出结构化 JSON: {searchQuery, tags[], styleHints,  │
│                               colorHints, compositionHints}      │
│                                                                 │
│  2b. Hybrid Retrieve（混合检索）                                  │
│      → PgVectorIndexService.search(query, oversample=20, 0.4)  │
│      → 对每个命中: keywordScore + metadataScore                 │
│                                                                 │
│  2c. Rerank（重排序）                                            │
│      → 加权公式: vector×50 + keyword×15 + metadata×10           │
│      → 截断 topK (默认5)                                        │
│  优先级: ★★☆ 中（AI 自动匹配）                                   │
└────────────────────────────────────────────────────────────────┘
                          ↓ 短路条件
┌─ Layer 3: 风格模板兜底（Style Template Fallback）───────────────┐
│  触发条件: Layer1 空 && Layer2 空 && message 非空               │
│                                                                 │
│  3a. 显式指定: styleTemplateCode 不为空 → getByCode(code)     │
│  3b. 关键词匹配: match(query) 遍历 10 套模板的 keywords          │
│                                                                 │
│  10 套模板: 水墨/赛博朋克/油画/素描/浮世绘/极简/复古/日系/水彩/电影感 │
│  优先级: ★☆☆ 低（没有参考图时的最后备选）                         │
└────────────────────────────────────────────────────────────────┘
```

## Layer 2 详细数据流

```
用户输入 "冬日雪景"
  │
  ├─ 2a: Query Rewrite（独立 ChatClient，无 Advisor）
  │      System Prompt: "你是图库检索查询改写助手..."
  │      Input: 用户需求："冬日雪景" + 最近20条对话
  │      Output JSON:
  │        {
  │          "searchQuery": "冬季雪景风光摄影",
  │          "category": "landscape",
  │          "tags": ["雪", "冬季", "自然"],
  │          "styleHints": ["写实", "风光"],
  │          "colorHints": ["白色", "冷色调"],
  │          "compositionHints": ["全景"],
  │          "referenceMode": "overall"
  │        }
  │      失败 → fallback: searchQuery = 原始消息
  │
  ├─ 2b: 混合检索
  │      向量检索: pgvector.search("冬季雪景风光摄影", topK=20, minScore=0.4)
  │        → embedding-2 嵌入 → cosine_distance 相似度
  │        → 返回 List<VectorSearchHit>
  │      关键词打分 (per hit):
  │        tag 完全匹配 +10, 部分匹配 +5
  │        名称包含检索词 +5
  │      元数据打分 (per hit):
  │        category 一致 +15
  │        styleHint 匹配 profile.style +10/个
  │        colorHint 匹配 profile.colors +10/个
  │        compositionHint 匹配 profile.composition +10/个
  │
  └─ 2c: 重排序
         **Phase 1 修复：批次内归一化** — 三个分值量纲不同（vectorScore 0~1，keywordScore 0~50+，metadataScore 0~50+），标签匹配易碾压语义。改为 min-max 归一化后再加权：

             finalScore = (vectorScore / maxVector) × vectorWeight
                        + (keywordScore / maxKeyword) × keywordWeight
                        + (metadataScore / maxMetadata) × metadataWeight

         finalScore = vectorScore × vectorWeight
                    + keywordScore × keywordWeight
                    + metadataScore × metadataWeight
         // 三个权重在 application.yml 中可配置
         → 降序排序 → .limit(5) → 附原因标签
```

### Layer 2 扩展：ReferenceRetriever 抽象 (Phase 3)

HybridGalleryRetriever → implements ReferenceRetriever 接口：

    ReferenceRetriever (接口)
      └── HybridGalleryRetriever extends ReferenceRetriever
            └── HybridGalleryRetrieverImpl

RagServiceImpl 面向 ReferenceRetriever 接口编程，未来可扩展 TemplateReferenceRetriever、DocumentReferenceRetriever 等。

## 权重可配置

```yaml
app:
  rag:
    enabled: true
    top-k: 5                # 最终返回的参考图数量
    min-score: 0.4           # pgvector cosine 最低阈值
    max-context-chars: 2500  # 注入 Prompt 的最大字符数
    retrieve-favorites-only: false
    vector-weight: 50.0      # 语义相似度权重
    keyword-weight: 15.0     # 标签/名称匹配权重
    metadata-weight: 10.0    # 分类/风格/色彩/构图匹配权重
```

**embedding-2 的 min-score 为什么是 0.4？** embedding-2 是智谱的嵌入模型，中文语义相关性余弦相似度通常在 0.4~0.5，0.65 的标准阈值会导致大量漏召回。经验值 0.4 在准确和召回之间取得平衡。

**Phase 1 新增开关**: `app.rag.enabled` 全局开关已生效。关闭时仅保留 Layer 1（用户明确指定的参考图），Layer 2+3 跳过。

## Context Packer：截断与裁剪

```java
RagContextPackerImpl.pack(ctx, criteria)
```

- **referenceMode 裁剪**: overall → 全部6个画像字段；style → 仅风格+氛围；color → 仅色彩+光影；composition → 仅构图
- **字符截断**: total > 2500 → 先截断 retrieved（优先级低），再截断 explicit（优先级高）
- **输出**: PackedRagContext{explicitText, retrievedText, templateText, totalChars}

## 图→图检索（P3 特性）

当用户上传参考图但**文本消息为空**时：

```
hasImage && message.isBlank()
  → visionAnalysisService.analyze("请提取可用于图库检索的视觉特征...")
  → buildImageSearchQuery(vision)  // 拼接 subject+style+colors+composition
  → 作为 RAG 检索的 query
  → msg = "基于上传的参考图片风格，请生成最终的图片生成参数"
```

## RAG 与 ChatMemory 的隔离

关键设计：RAG 增强文本**不写入** ChatMemory，避免记忆污染。

机制：RagInjectionAdvisor (order=15) 在 MCMA (内置) 之后执行。
MCMA 的 after() 在 order=15 之前已经将原始消息存入 ChatMemory。

参见 [Advisor 拦截链](../02-架构设计/Advisor拦截链.md) 中的时序分析。
