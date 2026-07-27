# RAG 中的检索方式有哪些？各有什么优劣？

> 标签: RAG, 检索 | 难度: 中等 | 来源: 面试扩展

---

## 一、核心结论

RAG 检索的主流工程路径通常是：

```
Dense Retrieval（语义召回）
  + BM25 / 关键词检索（精确召回）
  + Hybrid 融合（提升覆盖率）
  + Rerank 精排（提升最终相关性）
```

其中 Dense Retrieval 和 BM25 / 关键词是最常见的两类基础召回方式，Hybrid Retrieval 是生产中常见的组合方案，Rerank 通常作为检索后的精排步骤。ColBERT、GraphRAG、多跳检索、多模态检索等能力更适合作为特定场景下的增强项，而不是所有 RAG 系统的默认起点。

检索方式没有绝对最优，选型取决于数据类型、查询特点、延迟预算、标注数据、可维护性和评测结果。

---

## 二、检索方式全景：主流方案与增强方案

```
RAG 检索方式
├── 主流基础召回
│   ├── Dense Retrieval（稠密向量检索）
│   │   └── Bi-Encoder 单向量检索：最常见语义召回路径
│   ├── Sparse Retrieval（稀疏检索）
│   │   ├── BM25 / TF-IDF：经典关键词相关性算法
│   │   └── 关键词 / 标签 / 元数据匹配：高精度补充召回
│   └── Hybrid Retrieval（混合检索）
│       └── 向量 + BM25 / 关键词 + 元数据融合
│
├── 常见工程增强
│   ├── Rerank：Cross-Encoder、LLM Rerank、规则重排
│   ├── Self-Query：从自然语言中提取结构化过滤条件
│   ├── Parent Document Retrieval：小块召回，大块返回
│   └── Sentence Window Retrieval：返回命中句子的上下文窗口
│
└── 特定场景增强
    ├── ColBERT / 多向量检索：更细粒度但存储和部署成本更高
    ├── GraphRAG / 知识图谱检索：适合关系密集、多跳推理场景
    ├── Multi-Hop Retrieval：适合需要分解问题的复杂问答
    └── Multimodal Retrieval：适合图片、音频、视频等多模态数据
```

面试中建议先讲主流路径，再补充增强项的适用边界。直接把 GraphRAG、ColBERT 作为默认方案，容易显得选型不够工程化。

---

## 三、Dense Retrieval：稠密向量检索

### 3.1 单向量检索（Bi-Encoder）—— 主流语义召回方案

**原理**：用 Embedding 模型把文档块和用户查询分别编码成向量，在向量空间中根据余弦相似度、点积或 L2 距离检索 Top-K。

```
文档块 → Embedding 模型 → 向量 → 向量库 / ANN 索引
用户查询 → 同一 Embedding 模型 → 向量 → 相似度计算 → Top-K 候选
```

**为什么常用**：文档向量可以离线预计算，在线只需要编码查询并做近似最近邻搜索，性能和成本比较适合大规模知识库。

| 优点 | 局限 |
|------|------|
| 能召回语义相近但字面不一致的内容 | 对精确关键词、编号、专有名词可能漏召回 |
| 文档向量可预计算，在线检索速度较快 | 一个块通常压成一个向量，细粒度交互不足 |
| 向量数据库、Embedding 模型和框架生态成熟 | 效果强依赖模型、分块、距离度量和数据分布 |
| 适合 FAQ、文档问答、语义搜索等通用场景 | 换模型通常需要重建向量索引 |

**代表模型 / 工具**：OpenAI text-embedding-3、智谱 embedding-2、BGE、E5、GTE；向量索引可用 pgvector、FAISS、Milvus、Qdrant、Pinecone、Weaviate 等。

**面试追问：为什么查询和文档要用同一个 Embedding 模型？**

> 因为不同 Embedding 模型学习到的是不同向量空间，包括维度、分布、归一化方式和语义映射都可能不同。如果文档向量来自模型 A，查询向量来自模型 B，相似度通常没有可解释意义。工程上如果更换 Embedding 模型，一般需要重新向量化文档并重建索引。

### 3.2 Dense Retrieval 的常见工程问题

| 问题 | 说明 | 常见处理 |
|------|------|----------|
| 专有名词漏召回 | 向量模型不一定保留精确字面匹配 | 叠加 BM25 / 关键词召回 |
| 长文档信息损失 | 一个 chunk 压成一个向量可能丢失细节 | 合理分块、父文档检索、句子窗口 |
| Top-K 难调 | K 太小漏召回，K 太大噪声多 | 召回 K 稍大，再 Rerank 和压缩 |
| 相似度阈值不稳定 | 不同模型、领域、距离度量分数分布不同 | 基于验证集调阈值，而不是套固定值 |

---

## 四、Sparse Retrieval：稀疏检索

### 4.1 BM25 / TF-IDF —— 经典关键词相关性方案

**原理**：基于词频、逆文档频率和文档长度归一化，计算查询词与文档的字面匹配相关性。

```
score(q, d) = Σ IDF(q_i) × (TF(q_i, d) × (k1 + 1))
              / (TF(q_i, d) + k1 × (1 - b + b × |d| / avgdl))
```

| 优点 | 局限 |
|------|------|
| 精确匹配能力强，适合编号、术语、实体名 | 语义泛化能力弱，同义词可能无法召回 |
| 可解释性强，便于排查为什么命中 | 中文场景依赖分词和词典质量 |
| 成本低，不依赖 Embedding API 或 GPU | 无法真正理解上下文语义 |
| 对法规、代码、接口文档等精确文本友好 | 对表达多样的自然语言问题覆盖不足 |

**适用场景**：文档中有大量产品名、错误码、接口名、政策条款、类名、函数名、日志字段时，BM25 往往是向量检索的重要补充。

### 4.2 关键词 / 标签 / 元数据匹配

**原理**：基于标题、标签、分类、作者、时间、权限、业务字段等结构化信息做匹配或过滤。

```
query: "赛博朋克夜景"
  → tag = "赛博朋克" 命中
  → title 包含 "夜景" 命中
  → favorited = true 作为过滤条件
```

| 优点 | 局限 |
|------|------|
| 延迟低，可解释性强 | 依赖元数据质量和标注覆盖率 |
| 适合权限、时间、分类等硬条件 | 不能替代语义理解 |
| 可作为规则打分或过滤条件 | 规则过多会增加维护成本 |

工程上关键词和元数据一般不单独作为完整 RAG 检索方案，而是与向量召回、BM25、自查询和 Rerank 组合使用。

---

## 五、Hybrid Retrieval：混合检索

### 5.1 为什么混合检索常用

Dense Retrieval 擅长语义泛化，BM25 / 关键词擅长精确匹配。两者互补，因此生产 RAG 中常见做法是并行召回后融合：

```
                      ┌─ Dense Retrieval：语义召回 ─────┐
用户查询 → 查询预处理 ─┤                                  ├─ 合并去重 → 融合打分 → Rerank → Top-K
                      └─ BM25 / 关键词：精确召回 ───────┘
```

混合检索通常不是为了让链路更复杂，而是为了降低单一路径的漏召回风险。尤其在企业文档、代码库、工单、图片标签等场景中，语义和关键词往往都重要。

### 5.2 常见融合策略

| 策略 | 做法 | 适用边界 |
|------|------|----------|
| 线性加权 | 各路分数归一化后按权重求和 | 分数可校准、可通过评测调权重时 |
| RRF（Reciprocal Rank Fusion） | 按名次融合，`Σ 1 / (k + rank_i)` | 各路分数量纲不同、难以归一化时 |
| 规则融合 | 根据来源、标签、时间、权限等加减分 | 业务规则明确、需要可解释时 |
| 学习排序（LTR） | 用标注数据训练融合模型 | 有足够点击、人工标注或反馈数据时 |

**面试追问：RRF 和线性加权怎么选？**

> 线性加权依赖分数归一化，如果向量相似度、BM25 分数和规则分数的量纲不一致，直接相加会有偏差。RRF 只使用各路召回的排名，不依赖原始分数，因此更稳健，常用于初始融合。如果有验证集和稳定分数分布，可以尝试线性加权；如果分数难以校准，RRF 是更安全的基线。

### 5.3 Hybrid 不等于最终答案质量一定高

混合检索提升的是候选集合覆盖率，但候选多了也可能引入噪声。因此通常需要：

1. 合并去重，避免同一文档多个块重复占据上下文。
2. Rerank 精排，把真正相关的证据放到前面。
3. 上下文压缩，控制 Token 和噪声。
4. 离线评测，观察 Recall@K、MRR、NDCG、答案正确率和引用准确率。

---

## 六、Rerank：检索后的精排

Rerank 严格来说不是一种独立召回方式，而是召回后的排序增强。主流做法是先用 Dense / BM25 / Hybrid 召回较多候选，再用更精细但更慢的模型或规则进行重排。

| 方式 | 原理 | 优点 | 局限 |
|------|------|------|------|
| Cross-Encoder Rerank | query 和文档一起输入模型，输出相关性分数 | 精度通常高于 Bi-Encoder | 延迟和成本更高，只适合少量候选 |
| LLM Rerank | 让 LLM 判断候选相关性 | 可解释、灵活 | 成本高，稳定性需要约束 |
| 规则 Rerank | 根据时间、权限、业务标签、收藏状态等加权 | 可控、可解释 | 对语义相关性提升有限 |

**常见链路**：召回 Top-50 或 Top-100，Rerank 到 Top-5 / Top-10，再进行上下文组装。

---

## 七、Self-Query Retrieval：结构化条件提取

Self-Query 让 LLM 从自然语言中提取语义 query 和 metadata filter。

```
用户输入: "找去年上传的赛博朋克风格图片"
语义 query: "赛博朋克风格图片"
过滤条件: { uploadYear: 2025, sourceType: "upload" }
```

| 优点 | 局限 |
|------|------|
| 能处理时间、分类、权限、数值范围等硬条件 | 多一次 LLM 调用，增加延迟和成本 |
| 用户不需要了解数据库字段 | 依赖 Schema 描述和输出校验 |
| 可与向量检索、BM25、Hybrid 组合 | 模型解析错误时需要兜底 |

Self-Query 更适合“查询中经常包含结构化约束”的知识库，例如商品、图片、工单、日志、合同、内部文档。纯文本小知识库不一定需要引入。

---

## 八、特定场景增强方案

### 8.1 ColBERT / 多向量检索

ColBERT 不把整个文档块压成一个向量，而是为 token 或子片段保留多个向量，并在检索时做 late interaction。

```
Bi-Encoder：
  query 向量 ↔ doc 向量

ColBERT：
  query token 向量 ↔ doc token 向量，按 token 级最大相似度聚合
```

| 优点 | 局限 |
|------|------|
| 细粒度匹配能力更强 | 存储开销明显高于单向量 |
| 对长文本局部相关性更友好 | 检索和部署复杂度更高 |
| 在部分基准上效果优于单向量 | 不一定适合低延迟、低成本场景 |

定位：适合高精度文本检索、搜索质量要求高且能承受存储和部署成本的场景；一般不是企业 RAG 的第一版默认方案。

### 8.2 GraphRAG / 知识图谱检索

GraphRAG 利用实体、关系和社区结构补充普通向量检索，适合关系密集、需要跨文档关联的问题。

```
问题："某公司收购后，相关产品线负责人和历史项目有什么变化？"
普通向量检索：召回若干文档片段
图检索：实体 → 关系 → 社区摘要 → 相关证据
```

| 优点 | 局限 |
|------|------|
| 适合关系推理、实体关联和全局主题总结 | 图谱构建、实体抽取、关系维护成本高 |
| 可解释性较好 | 数据更新和错误传播需要治理 |
| 能补充单纯向量检索的全局视角 | 对简单 FAQ 或小规模知识库可能过度设计 |

定位：适合组织知识、研究报告、法律案件、复杂项目资料等关系密集场景；不建议在简单文档问答中默认引入。

### 8.3 Multi-Hop Retrieval：多跳检索

多跳检索适合一次查询无法直接得到完整证据的问题。系统会先检索一部分证据，再根据中间结果生成后续查询。

```
复杂问题 → 子问题 1 → 检索证据 A
        → 子问题 2 → 检索证据 B
        → 合并证据 → 生成答案
```

优点是能处理组合型问题，代价是链路变长、延迟增加，并且每一跳的错误可能传递到后续步骤。工程上需要设置最大跳数、证据不足兜底和中间结果可观测性。

### 8.4 Multimodal Retrieval：多模态检索

多模态检索用 CLIP 等模型把图片和文本映射到同一或可对齐的向量空间，支持文搜图、图搜图、图文混合检索。

```
图片 → 视觉编码器 → image embedding
文本 → 文本编码器 → text embedding
二者在共享语义空间中计算相似度
```

适合图片库、设计素材库、电商图片、视频帧检索等场景。局限是模型选择、向量融合、模态间分数校准和版权/隐私处理更复杂。

---

## 九、其他常见工程策略

### 9.1 Parent Document Retrieval

索引用小 chunk，返回时带上父文档或父章节，兼顾匹配精度和上下文完整性。

```
索引：小块 Embedding
检索：命中小块
返回：小块 + 所属章节 / 父文档
```

适合章节结构清晰、单个 chunk 容易丢上下文的文档。

### 9.2 Sentence Window Retrieval

命中某个句子或小块后，返回其前后 N 句作为上下文窗口。相比父文档，它返回范围更小，Token 成本更可控。

### 9.3 Metadata Filter / 权限过滤

生产 RAG 必须考虑权限过滤和数据隔离。常见做法是在检索前或检索时加入 tenantId、userId、department、visibility、documentStatus 等过滤条件，避免把用户无权访问的内容注入 Prompt。

---

## 十、检索方式选择建议

```
默认起点：
  文本知识库 → Dense Retrieval + BM25 / 关键词 → Hybrid → Rerank

如果查询有明确条件：
  加 Self-Query / Metadata Filter

如果文档很长：
  加 Parent Document / Sentence Window / Context Compression

如果需要关系推理：
  评估 GraphRAG / 知识图谱 / 多跳检索

如果数据是图片、音频、视频：
  评估 Multimodal Embedding 或先转文本画像再检索

如果对检索精度要求极高且可接受成本：
  评估 ColBERT、多向量检索或更强 Rerank
```

选型时不建议只凭经验判断，应构建小规模评测集，至少观察：Recall@K、MRR、NDCG、Rerank 后命中率、最终答案正确率、引用准确率、延迟和成本。

---

## 十一、面试回答模板

> RAG 的检索方式可以分成主流基础召回和增强方案。生产中最常见的基础路径是 Dense Retrieval 加 BM25 或关键词检索，再做 Hybrid 融合和 Rerank。Dense Retrieval 负责语义召回，能找到字面不同但含义接近的文档；BM25 和关键词负责精确匹配，适合专有名词、编号、接口名、错误码等场景。两者互补，所以混合检索通常比单一路径更稳。
>
> 在融合上，如果各路分数能校准，可以做归一化后的线性加权；如果分数量纲差异较大，RRF 是更稳健的基线。召回后通常会用 Cross-Encoder、LLM 或业务规则做 Rerank，再把 Top-K 文档做去重、压缩和引用组装。
>
> ColBERT、GraphRAG、多跳检索、多模态检索这些方案也有价值，但我会把它们定位为增强项：ColBERT 适合高精度文本检索但存储成本高；GraphRAG 适合关系密集和多跳推理；多模态检索适合图片或视频数据。实际选型要结合数据特点、延迟预算和离线评测结果，而不是默认引入最复杂的方案。

---

## 十二、参考来源 / 延伸阅读

1. Karpukhin et al., **Dense Passage Retrieval for Open-Domain Question Answering**, EMNLP 2020. https://arxiv.org/abs/2004.04906
2. Robertson and Zaragoza, **The Probabilistic Relevance Framework: BM25 and Beyond**, 2009. https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf
3. Khattab and Zaharia, **ColBERT: Efficient and Effective Passage Search via Contextualized Late Interaction over BERT**, SIGIR 2020. https://arxiv.org/abs/2004.12832
4. Cormack et al., **Reciprocal Rank Fusion Outperforms Condorcet and Individual Rank Learning Methods**, SIGIR 2009. https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf
5. LangChain Documentation, **Retrieval**. https://python.langchain.com/docs/concepts/retrieval/
6. pgvector Documentation, **Open-source vector similarity search for Postgres**. https://github.com/pgvector/pgvector
