# 什么是 RAG 混合检索？如何实现向量检索和关键词检索结合？

> 标签: RAG, 混合检索, 向量检索, BM25 | 难度: 🟡 中等 | 编号: QA20

---

## 一、核心定义

RAG 混合检索（Hybrid Retrieval）是指在召回阶段同时使用**向量检索**和**关键词 / BM25 检索**，再对两路候选结果进行去重、分数融合、重排序和上下文压缩，最终选出最相关的文档片段注入 Prompt。

它的核心价值是：

- 向量检索负责语义泛化，解决同义表达、模糊描述、自然语言问题；
- 关键词检索负责精确匹配，解决专有名词、编号、代码符号、版本号、错误码等问题；
- 融合排序负责把两路结果合并成稳定、可解释的最终 Top-K。

```text
用户问题
  ├─ 向量检索：Embedding → Vector Search → semantic candidates
  └─ 关键词检索：BM25 / 倒排索引 → lexical candidates
          ↓
    合并去重 → RRF / 归一化加权 → Rerank → 最终上下文
```

---

## 二、为什么不能只用向量检索

向量检索是 RAG 的主流召回方式，但并不覆盖所有场景。

| 场景 | 单纯向量检索的问题 | 关键词检索的补充价值 |
|------|-------------------|---------------------|
| 专有名词 | 内部系统名、产品名可能不在 Embedding 训练分布中 | 精确词项命中 |
| 编号 / 错误码 | `ERR-1024`、`API-2025-A` 语义信息弱 | 倒排索引直接匹配 |
| 代码 / 配置 | 函数名、类名、配置项通常不是自然语言 | 关键词路径更稳定 |
| 短查询 | 只有 1~3 个词时，向量语义信号不足 | BM25 对标题和词频敏感 |
| 版本条件 | `Spring AI 1.0 GA`、`v2.3` 需要精确约束 | 关键词和 metadata filter 更可靠 |

因此，生产 RAG 中常见做法不是“向量检索 or 关键词检索”二选一，而是用混合检索提升召回稳健性。

---

## 三、主流实现架构

### 3.1 索引阶段：同一份 chunk 建两类索引

混合检索的前提是同一批文档 chunk 同时支持语义召回和词项召回。

```text
原始文档
  ↓
解析 / 清洗 / 分块
  ↓
chunk_text + metadata
  ├─ Embedding 模型 → 向量 → 向量库 / pgvector / Milvus / Qdrant
  └─ 分词 / Analyzer → 倒排索引 → Elasticsearch / OpenSearch / Lucene / PostgreSQL FTS
```

每个 chunk 至少要保留：

| 字段 | 作用 |
|------|------|
| `chunk_id` | 两路结果去重和融合的稳定主键 |
| `doc_id` | 回溯原文档 |
| `content` | 原文片段 |
| `embedding` | 向量检索使用 |
| `metadata` | 权限、时间、分类、来源、标题等过滤条件 |
| `keywords / analyzed_text` | BM25 或全文检索使用 |

工程上可以有两种部署方式：

| 方式 | 示例 | 特点 |
|------|------|------|
| 单系统承载 | Elasticsearch / OpenSearch 同时支持向量和 BM25 | 架构简单，适合快速落地 |
| 双系统承载 | pgvector / Milvus 负责向量，Elasticsearch / PostgreSQL FTS 负责关键词 | 可按能力选型，但需要结果融合层 |

---

## 四、在线查询阶段如何结合

### 4.1 查询预处理

先对用户问题做轻量处理：

- 多轮场景中做 Query Rewrite，补全省略指代；
- 提取 metadata filter，例如时间、分类、租户、权限；
- 保留原始查询用于关键词检索，改写查询用于向量检索；
- 对代码、编号、错误码等模式做识别，必要时提高关键词路权重。

```text
原始问题: “Spring AI 1.0 的 Advisor 怎么配置？”
向量查询: “Spring AI 1.0 ChatClient Advisor configuration usage”
关键词查询: “Spring AI 1.0 Advisor 配置”
metadata filter: version = 1.0
```

### 4.2 双路召回

```text
Dense 路：
query → embedding → vector_search(topK=30, filter=metadata)

Sparse 路：
query → BM25 / keyword_search(topK=30, filter=metadata)
```

两路 Top-K 不一定相同。若业务中专有名词、错误码、代码符号较多，可以适当提高关键词路候选数；若用户查询偏自然语言，可以提高向量路候选数。具体比例应通过离线评测调整。

### 4.3 合并去重

同一个 chunk 可能同时被两路命中，应按 `chunk_id` 去重，并保留两路分数和排名。

```text
candidate = {
  chunk_id,
  content,
  dense_score,
  dense_rank,
  sparse_score,
  sparse_rank,
  metadata
}
```

如果同一文档多个相邻 chunk 命中，可以在后续 Context Pack 阶段合并相邻片段，避免重复占用上下文窗口。

---

## 五、融合排序怎么做

### 5.1 RRF：最稳健的常用方案

由于向量相似度和 BM25 分数的数值分布不同，直接相加通常不可靠。工程上常用 RRF（Reciprocal Rank Fusion）按排名融合：

```text
RRF_score(d) = Σ 1 / (k + rank_i(d))
```

其中：

- `rank_i(d)` 表示文档在第 i 路检索结果中的排名；
- `k` 是平滑常数，常见取值约为 60；
- 同时被多路召回、且排名靠前的文档会获得更高分数。

RRF 的优势是对原始分数不敏感，适合 BM25 与向量相似度量纲不一致的场景。

### 5.2 归一化加权：适合有评测集的场景

如果系统有稳定评测集，可以对两路分数做归一化后加权：

```text
final_score = α * normalized_dense_score
            + β * normalized_sparse_score
            + γ * metadata_score
```

注意点：

- 不能直接把 BM25 分数和 cosine similarity 相加；
- min-max、z-score、分位数归一化都需要结合数据分布验证；
- 权重不应凭感觉固定，应通过 Recall@K、nDCG、MRR、引用命中率等指标调参。

### 5.3 Rerank：融合后的精排阶段

混合检索主要解决“候选召回更全面”，但最终注入 Prompt 前通常还需要 Rerank：

```text
Dense Top-30 + Sparse Top-30
  ↓
RRF / 加权融合 Top-40
  ↓
Cross-Encoder Rerank Top-10
  ↓
MMR / Context Compression Top-5
  ↓
Prompt
```

Rerank 可以进一步判断 query 和 chunk 的细粒度相关性，减少仅因关键词命中但语义无关的候选进入上下文。

---

## 六、简化伪代码

```java
List<Candidate> hybridSearch(String query, SearchFilter filter) {
    // 1. 查询改写与特征提取
    String rewrittenQuery = queryRewrite(query);

    // 2. 向量召回
    float[] queryVector = embeddingModel.embed(rewrittenQuery);
    List<Candidate> denseResults = vectorStore.search(queryVector, filter, 30);

    // 3. 关键词 / BM25 召回
    List<Candidate> sparseResults = keywordSearch.search(query, filter, 30);

    // 4. 合并去重，保留 dense_rank / sparse_rank
    List<Candidate> merged = mergeByChunkId(denseResults, sparseResults);

    // 5. RRF 或归一化加权融合
    List<Candidate> fused = rrfRank(merged);

    // 6. 精排与上下文压缩
    List<Candidate> reranked = reranker.rerank(query, fused, 10);
    return contextPacker.pack(reranked, 5);
}
```

这段伪代码表达的是主流工程链路，具体实现可以使用 Spring AI 的 `VectorStore`、Elasticsearch / OpenSearch、PostgreSQL FTS、Lucene 或其他检索组件组合完成。

---

## 七、工程落地要点

1. **权限过滤前置**：租户、用户权限、文档状态应在两路召回前过滤，避免检索到不可见内容。
2. **保留两路召回 trace**：记录候选来自 dense、sparse 还是两者同时命中，便于排查召回缺陷。
3. **不要直接相加分数**：向量分和 BM25 分数量纲不同，优先用 RRF 或归一化加权。
4. **分词器要适配领域**：中文、代码、产品名、缩写、错误码需要合适 analyzer 和同义词表。
5. **Top-K 分阶段设置**：初检 Top-K 可以较大，最终注入 Prompt 的 Top-K 要受上下文窗口和相关性控制。
6. **配合 Rerank 与上下文压缩**：混合检索解决召回，Rerank 和 Context Pack 解决精度与上下文预算。
7. **建立离线评测集**：通过 Recall@K、nDCG、MRR、Context Precision、答案引用命中率评估，而不是只看少量人工样例。

---

## 八、常见误区

| 误区 | 更准确的说法 |
|------|--------------|
| 混合检索就是向量分 + BM25 分直接相加 | 两路分数量纲不同，通常需要 RRF、归一化或学习排序 |
| 有了 Embedding 就不需要 BM25 | Embedding 对编号、代码、专名不一定稳定，BM25 仍是重要补充 |
| 混合检索一定优于单路检索 | 如果知识库小、查询稳定、语料规范，单路检索可能已足够 |
| Rerank 可以替代混合检索 | Rerank 只能重排已召回候选，无法补救召回阶段完全漏掉的文档 |
| 关键词检索只是简单字符串匹配 | 生产中通常涉及 analyzer、BM25、同义词、字段权重和 metadata filter |

---

## 九、面试回答模板

> RAG 混合检索是把向量检索和关键词 / BM25 检索结合起来的召回方案。向量检索擅长语义相似和模糊表达，但对错误码、版本号、代码符号、内部专有名词不一定稳定；关键词检索擅长精确匹配，但缺少语义泛化能力。所以工程上通常会对同一批 chunk 同时建立向量索引和倒排索引，在线查询时双路并行召回，再按 chunk_id 合并去重。融合时不能直接把 BM25 分数和向量相似度相加，常见做法是用 RRF 按排名融合，或者在有评测集的情况下做归一化加权。融合后的候选通常还会接 Cross-Encoder Rerank、MMR 去冗余和上下文压缩，最终只把最相关的少量片段注入 Prompt。落地时重点关注权限过滤、分词器、分数融合、召回 trace 和 Recall@K、nDCG 等评测指标。

---

## 十、和 QA10 的关系

QA10 侧重回答“混合检索是什么、主要解决什么问题”；本题更偏工程实现，重点是索引结构、双路召回、候选融合、Rerank 和评测指标。两者可以结合复习：先讲 QA10 的概念和价值，再用本题展开具体实现。

---

## 十一、参考来源 / 延伸阅读

1. Robertson, S. and Zaragoza, H. *The Probabilistic Relevance Framework: BM25 and Beyond*. Foundations and Trends in Information Retrieval, 2009. https://www.nowpublishers.com/article/Details/INR-019
2. Cormack, G. V., Clarke, C. L. A., and Buettcher, S. *Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods*. SIGIR, 2009. https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf
3. Elasticsearch Documentation: Hybrid search. https://www.elastic.co/guide/en/elasticsearch/reference/current/semantic-text-hybrid-search.html
4. OpenSearch Documentation: Hybrid search. https://opensearch.org/docs/latest/search-plugins/hybrid-search/
5. Spring AI Reference: Vector Databases / VectorStore. https://docs.spring.io/spring-ai/reference/api/vectordbs.html
6. LangChain Documentation: Retriever concepts and hybrid search integrations. https://python.langchain.com/docs/concepts/retrievers/
