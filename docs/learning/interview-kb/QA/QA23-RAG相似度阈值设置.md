# RAG 检索时相似度阈值如何设置？设置不当有什么影响？

> 标签: RAG, 相似度阈值, 检索过滤, 向量检索 | 难度: 🟡 中等 | 编号: QA23

---

## 一、核心结论

RAG 中的相似度阈值（similarity threshold）用于过滤低相关检索结果，避免把明显无关的 chunk 注入 Prompt。它不能只凭经验固定，应结合**Embedding 模型、距离度量、业务语料、Top-K、Rerank、拒答策略和离线评测集**共同校准。

核心原则：

- 阈值过高：容易漏召回，系统经常找不到资料或错误拒答；
- 阈值过低：容易引入噪声，模型基于无关上下文生成错误答案；
- 最佳阈值通常不是全局常数，而是按知识库、文档类型、查询类型或检索阶段动态调整。

---

## 二、先区分“相似度”和“距离”

不同向量库返回的分数含义不同，设置阈值前必须先确认排序方向。

| 分数类型 | 含义 | 越大越相似？ |
|----------|------|--------------|
| cosine similarity | 余弦相似度 | 是 |
| cosine distance | 余弦距离，常见为 `1 - cosine_similarity` | 否，越小越相似 |
| inner product / dot product | 内积或点积 | 通常越大越相似 |
| L2 distance | 欧氏距离 | 否，越小越相似 |

以 pgvector 的 cosine distance 为例：

```sql
ORDER BY embedding <=> query_embedding
```

`<=>` 返回的是距离，数值越小越相似。如果业务希望使用相似度阈值，可以转换：

```text
similarity = 1 - cosine_distance
```

因此，“阈值大于 0.75 才保留”还是“距离小于 0.25 才保留”，取决于系统内部使用的是 similarity 还是 distance。

---

## 三、阈值设置的主流方法

### 3.1 先建立离线评测集

准备一批真实 query，并标注标准证据：

```text
query: “Spring AI 中 MessageChatMemoryAdvisor 的作用是什么？”
gold_context: spring-ai-reference#message-chat-memory-advisor
```

评测集应包含：

- 高频问题；
- 长尾问题；
- 专有名词 / 编号 / 代码类问题；
- 无答案问题；
- 相似但容易混淆的问题；
- 多知识库或权限边界问题。

没有评测集时，阈值只能是经验值，容易在上线后出现误召回或漏召回。

### 3.2 观察正负样本分数分布

对评测集跑检索，分别统计相关 chunk 和无关 chunk 的分数分布。

```text
相关 chunk similarity: 0.62 ~ 0.86，集中在 0.70 左右
无关 chunk similarity: 0.20 ~ 0.58，集中在 0.40 左右
```

如果两类分布有明显间隔，可以设置较明确的阈值；如果分布重叠严重，说明单靠向量分数不够，需要混合检索、Rerank 或 metadata 过滤。

### 3.3 用指标选择阈值

不同阈值下观察：

| 指标 | 作用 |
|------|------|
| Recall@K | 正确证据是否被保留 |
| Precision@K | 注入候选中相关内容比例 |
| Context Precision | 最终上下文噪声是否可控 |
| Answer Correctness | 最终答案是否正确 |
| Faithfulness | 答案是否忠实于上下文 |
| Refusal Accuracy | 无资料时是否正确拒答 |
| 空检索率 | 阈值是否过严导致经常无结果 |

一般不建议只追求 Precision，也不建议只追求 Recall。RAG 更关注“最终答案质量 + 可接受成本”。

---

## 四、常见经验起点

阈值没有跨模型通用值。不同 Embedding 模型、归一化方式、语言、语料和向量库实现都会改变分数分布。

可以采用如下思路作为起点：

| 场景 | 阈值策略 |
|------|----------|
| 通用知识库 | 先用相对宽松阈值保证 Recall，再通过 Rerank 控制噪声 |
| 高可信问答 | 阈值适当提高，并配合拒答和引用校验 |
| 专有名词 / 代码场景 | 不只依赖向量阈值，应叠加 BM25 / 关键词检索 |
| 短查询 | 阈值不要过高，先做 Query Rewrite 或历史补全 |
| 无答案检测 | 设置低置信区间，触发拒答或澄清问题 |

例如，在某些中文 Embedding 模型中，语义相关内容的 cosine similarity 可能集中在 0.4~0.7；如果直接套用其他模型 0.8 的经验阈值，可能导致大量漏召回。因此阈值必须基于本项目语料重新评估。

---

## 五、阈值和 Top-K、Rerank 的关系

相似度阈值不是孤立参数，通常和 Top-K、Rerank 配合使用。

```text
向量检索 Top-50
  ↓
相似度阈值过滤，去掉明显无关候选
  ↓
Rerank Top-10
  ↓
Context Pack Top-5
  ↓
LLM 生成 / 拒答
```

常见策略：

1. 初检 Top-K 适当放大，保证召回；
2. 相似度阈值过滤掉低质量候选；
3. Rerank 对剩余候选做精排；
4. 若过滤后候选为空或分数整体偏低，触发拒答或澄清。

不要把阈值当作唯一质量控制手段。向量相似度只能衡量语义接近，不等价于事实支撑关系。

---

## 六、设置不当的影响

### 6.1 阈值过高

| 影响 | 说明 |
|------|------|
| 漏召回 | 正确文档分数略低时被过滤 |
| 空检索率升高 | 系统经常回答“知识库无相关资料” |
| 用户体验下降 | 明明知识库有答案，但系统找不到 |
| 对短查询不友好 | 短查询 Embedding 不稳定，更容易被高阈值过滤 |
| 过度依赖模型常识 | 检索为空后，若拒答策略不严格，模型可能凭参数知识回答 |

### 6.2 阈值过低

| 影响 | 说明 |
|------|------|
| 上下文噪声增加 | 无关 chunk 被注入 Prompt |
| 幻觉风险升高 | 模型可能基于错误上下文生成答案 |
| 引用质量下降 | 引用看似存在，但不支撑结论 |
| Token 成本增加 | 更多无效上下文进入模型 |
| Rerank 压力增大 | 精排阶段需要处理更多低质量候选 |

---

## 七、工程落地建议

1. **按模型和语料单独校准**：更换 Embedding 模型或知识库后，阈值需要重新评估。
2. **区分 hard threshold 和 soft threshold**：低于硬阈值直接过滤；低置信区间可进入 Rerank 或触发澄清。
3. **按查询类型动态调整**：短查询、专名查询、多轮查询、长问题可以采用不同阈值策略。
4. **结合混合检索**：专有名词、编号、代码符号不能只靠向量阈值判断。
5. **记录分数分布和空检索率**：线上监控低分率、空检索率、拒答率、用户追问率。
6. **与引用校验联动**：高分 chunk 也不一定支撑答案，最终仍需要引用和事实支撑校验。

---

## 八、面试回答模板

> RAG 的相似度阈值用于过滤低相关检索结果，但它没有通用固定值，必须结合 Embedding 模型、距离度量和业务语料评估。首先要确认向量库返回的是 similarity 还是 distance，比如 pgvector 的 cosine distance 是越小越相似，而 cosine similarity 是越大越相似。工程上我会先构建离线评测集，标注 query 对应的标准证据，观察相关和无关 chunk 的分数分布，再用 Recall@K、Precision@K、Context Precision、Faithfulness、空检索率和拒答准确率来选择阈值。阈值过高会导致漏召回和错误拒答，阈值过低会引入上下文噪声、增加幻觉和 token 成本。实际落地时通常是初检 Top-K 放宽一点，用阈值过滤明显无关候选，再接 Rerank 和上下文压缩，并对低置信结果触发拒答或澄清。

---

## 九、参考来源 / 延伸阅读

1. pgvector 官方文档：https://github.com/pgvector/pgvector
2. Spring AI VectorStore 文档：https://docs.spring.io/spring-ai/reference/api/vectordbs.html
3. FAISS MetricType and distances：https://github.com/facebookresearch/faiss/wiki/MetricType-and-distances
4. RAGAS 文档：Context Precision / Context Recall / Faithfulness：https://docs.ragas.io/
5. LangChain Retriever 相关文档：https://python.langchain.com/docs/concepts/retrievers/
