# RAG 中的查询重写 Query Rewriting 是什么？如何优化检索效果？

> 标签: RAG, Query Rewriting, 查询改写, 检索优化 | 难度: 🟡 中等 | 编号: QA26

---

## 一、核心定义

Query Rewriting（查询重写）是在检索前对用户原始问题进行改写，使其更完整、明确、适合检索。它的目标不是改变用户意图，而是把口语化、省略、指代不清或多轮上下文依赖的问题转换成更稳定的检索 query。

```text
原始问题：它怎么配置？
对话上下文：用户上一轮在问 Spring AI PgVectorStore
重写后：Spring AI PgVectorStore 如何配置数据库连接、向量维度和距离度量？
```

核心原则：**重写是澄清和规范化，不是扩写事实、添加结论或替用户创造需求。**

---

## 二、Query Rewriting 解决什么问题

| 问题类型 | 原始查询 | 重写后 |
|----------|----------|--------|
| 指代不清 | 它和 Rerank 有什么区别？ | 混合检索和 Rerank 有什么区别？ |
| 省略上下文 | 怎么设置？ | RAG 中 chunk_size 和 overlap 如何设置？ |
| 口语表达 | 查不准咋办？ | RAG 检索准确率低如何优化？ |
| 术语不统一 | 知识库搜不到 | RAG 知识库向量检索召回不到相关文档的原因是什么？ |
| 多意图混合 | RAG 慢还不准怎么办？ | 拆成：如何降低 RAG 延迟？如何提升 RAG 检索准确率？ |

在多轮 RAG 中，Query Rewrite 往往是必备能力，因为当前用户问题经常依赖历史上下文。

---

## 三、查询重写和查询扩展的区别

| 维度 | Query Rewriting | Query Expansion |
|------|-----------------|-----------------|
| 核心动作 | 改写表达，使问题更清晰 | 增加同义词、相关术语或多个查询 |
| 目标 | 提高语义准确性 | 提高召回覆盖率 |
| 输出 | 通常是一个更完整 query | 可能是多个 query 或扩展词列表 |
| 风险 | 改写偏离原意 | 扩展过度引入噪声 |
| 主流场景 | 多轮问答、省略、指代、口语化问题 | 词汇不匹配、短查询、领域术语差异 |

工程上常见顺序是：先 Query Rewrite 消除歧义，再按需做 Query Expansion、Self-Query 或 Multi-Query。

---

## 四、主流实现方式

### 4.1 基于规则的轻量改写

适合固定模式明显的场景：

- 替换常见同义词；
- 补全缩写；
- 规范大小写；
- 识别错误码、版本号、产品名；
- 将“上一个”“它”等简单指代替换为当前话题。

优点是低延迟、可控；缺点是覆盖面有限。

### 4.2 基于 LLM 的上下文改写

这是当前 RAG 系统中最常见的方式。将用户当前问题和最近若干轮对话交给 LLM，输出一个独立、完整、适合检索的问题。

Prompt 示例：

```text
你是查询重写器。请根据对话历史，将当前用户问题改写为一个独立、明确、适合检索的查询。
要求：
1. 保留用户原始意图；
2. 不添加未出现的事实或约束；
3. 不回答问题；
4. 只输出重写后的查询。

对话历史：...
当前问题：它怎么配置？
```

输出：

```text
Spring AI PgVectorStore 如何配置数据库连接、向量维度和距离度量？
```

### 4.3 结构化重写

为了降低 LLM 输出不稳定，生产中可以要求输出 JSON：

```json
{
  "rewritten_query": "Spring AI PgVectorStore 如何配置数据库连接和向量维度？",
  "intent": "configuration_question",
  "filters": {
    "framework": "Spring AI",
    "component": "PgVectorStore"
  },
  "confidence": 0.86
}
```

然后服务端校验字段、过滤非法 filter，并决定是否进入检索。

### 4.4 Multi-Query Rewrite

对于宽泛问题，可以生成多个检索 query：

```text
原问题：如何优化 RAG 效果？
Query 1：如何提升 RAG 检索召回率？
Query 2：如何通过 Rerank 提升 RAG 上下文质量？
Query 3：如何减少 RAG 生成阶段幻觉？
```

多 query 可以提升召回覆盖，但会增加检索成本和融合复杂度，应配合 RRF、去重和 Rerank。

---

## 五、如何优化检索效果

### 5.1 补全多轮上下文

把依赖历史的问题改写为独立 query：

```text
历史：用户问“介绍一下 RAG 混合检索”
当前：它和自查询有什么区别？
重写：RAG 混合检索和 Self-Query 检索有什么区别？
```

这能显著降低“它”“这个”“上面那个”导致的检索失败。

### 5.2 保留关键词和专有名词

LLM 改写时可能把错误码、版本号、类名、方法名改掉，导致关键词检索失败。因此 Prompt 中要明确要求保留：

- 产品名；
- 版本号；
- API 名称；
- 错误码；
- 类名、函数名、配置项；
- 用户原始关键词。

### 5.3 输出原始 query + 重写 query 双路检索

为了避免重写偏移，可以同时检索原始查询和重写查询：

```text
原始 query → BM25 / keyword search
重写 query → vector search
两路结果 → 融合 / Rerank
```

这在混合检索中很实用：原始 query 保留精确词项，重写 query 提供完整语义。

### 5.4 限制重写范围

不允许模型引入未出现的约束：

```text
用户：RAG 怎么优化？
错误重写：如何使用 LangChain 和 Elasticsearch 优化企业 RAG？
问题：用户没有指定 LangChain 和 Elasticsearch
```

正确重写应保持中立：

```text
RAG 系统可以从哪些方面优化检索质量、上下文质量和生成质量？
```

### 5.5 对低置信重写触发回退

如果重写模型置信度低，或检测到重写前后意图差异过大，可以：

- 使用原始查询检索；
- 同时检索原始和重写查询；
- 向用户澄清；
- 降低重写结果权重。

---

## 六、工程落地要点

1. **不要让重写器回答问题**：它只生成检索 query，不生成最终答案。
2. **限制历史窗口**：只使用最近相关对话，避免旧话题污染。
3. **保留原始查询**：日志、召回 trace 和问题排查都需要原始输入。
4. **结构化输出更稳定**：复杂场景建议输出 query、intent、filters、confidence。
5. **服务端校验 filter**：LLM 生成的结构化过滤条件不能直接信任。
6. **与混合检索配合**：原始查询走关键词，重写查询走向量，常比单路更稳健。
7. **评测驱动优化**：用 Recall@K、MRR、nDCG、答案正确率和延迟成本评估重写收益。

---

## 七、常见误区

| 误区 | 更准确的说法 |
|------|--------------|
| Query Rewrite 就是把问题写得更长 | 核心是消除歧义和补全上下文，不是盲目变长 |
| LLM 重写一定更好 | 重写可能偏离原意，应保留原始 query 并做评测 |
| 改写后只用新 query 检索 | 原始 query 保留专名和精确词项，常应参与关键词检索 |
| 重写可以替代 Rerank | 重写优化召回输入，Rerank 优化候选排序，阶段不同 |
| 可以直接信任 LLM 生成的 filter | filter 必须经过 schema 校验、权限校验和字段白名单 |

---

## 八、面试回答模板

> Query Rewriting 是 RAG 检索前的查询改写环节，目标是把用户口语化、省略、指代不清或依赖上下文的问题，改写成一个独立、明确、适合检索的 query。它和 Query Expansion 不同，Rewrite 主要是澄清和规范化表达，Expansion 是增加同义词或多个查询来扩大召回。工程上常用 LLM 结合最近对话历史做改写，也可以要求输出结构化 JSON，包括 rewritten_query、intent、filters 和 confidence。优化检索效果时，要保留原始意图，不能添加用户没说过的约束；要保留版本号、错误码、API 名等精确关键词；多轮场景要补全“它”“上一个”等指代；同时可以让原始 query 走 BM25，重写 query 走向量检索，再做融合和 Rerank。最终是否有效要用 Recall@K、MRR、nDCG、答案正确率、延迟和成本评估。

---

## 九、参考来源 / 延伸阅读

1. LangChain Query Analysis / Query Transformation 文档：https://python.langchain.com/docs/concepts/retrieval/
2. LangChain MultiQueryRetriever 文档：https://python.langchain.com/docs/how_to/MultiQueryRetriever/
3. LlamaIndex Query Transformations 文档：https://docs.llamaindex.ai/en/stable/optimizing/advanced_retrieval/query_transformations/
4. Gao, L. et al. *Precise Zero-Shot Dense Retrieval without Relevance Labels* (HyDE). ACL, 2023. https://arxiv.org/abs/2212.10496
5. Spring AI Advisors / ChatClient 文档：https://docs.spring.io/spring-ai/reference/api/chatclient.html
