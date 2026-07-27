# 什么是自查询？为什么在 RAG 中需要自查询？

> 标签: RAG, 检索 | 难度: 简单 | 来源: 面试鸭

---

## 一、一句话定义

**自查询（Self-Query）** 是让 LLM 从用户自然语言中同时提取两类信息：

1. **语义检索词**：用于向量检索或关键词检索。
2. **结构化过滤条件**：用于元数据精确过滤，例如时间、作者、分类、权限、标签、价格区间等。

本质上，自查询是在 RAG 检索前增加一层“自然语言 → 查询计划”的转换，把模糊语义匹配和确定性条件过滤结合起来。

---

## 二、为什么需要自查询

普通向量检索主要依赖语义相似度，但真实业务问题经常包含精确约束：

```
用户说：“帮我找去年夏天拍的那些日系风格照片”

普通向量检索：
  query = “去年夏天拍的日系风格照片”
  问题：向量相似度不擅长严格区分年份、季节、来源、权限等结构化条件。

自查询检索：
  query = “日系风格照片”
  filter = { year: 2025, season: "summer", owner: "me" }
  结果：先用过滤条件缩小候选范围，再做语义召回或排序。
```

**核心价值**：把“去年”“我收藏的”“某个项目下”“价格小于 1000”这类隐含结构化意图，转换为可执行过滤条件，避免只依赖向量相似度。

---

## 三、具体流程

```
用户输入："找一些我去年收藏的、暖色调的、赛博朋克城市夜景图"

   ↓ LLM 解析查询意图，输出结构化 JSON

{
  "query": "赛博朋克城市夜景 暖色调",
  "filter": {
    "year": 2025,
    "favorited": true,
    "colorTone": "warm",
    "style": "cyberpunk"
  }
}

   ↓ 执行检索

1. 元数据过滤：WHERE year = 2025 AND favorited = true ...
2. 语义检索：Embedding(query) → 向量相似度召回
3. 合并候选：过滤后的候选集进入 Rerank
4. 输出 Top-K：返回最相关证据给 LLM
```

工程实现中可以有两种执行顺序：

| 执行方式 | 说明 | 适用场景 |
|----------|------|----------|
| 先过滤后向量检索 | 先用元数据缩小范围，再在子集内向量搜索 | 数据库支持过滤向量检索，过滤条件选择性强 |
| 先召回后过滤 | 先向量召回较大候选集，再做条件过滤 | 向量库过滤能力较弱，或过滤条件较松 |

---

## 四、LLM 需要知道什么

自查询依赖清晰的元数据 Schema。Prompt 中通常要告诉模型：哪些字段可过滤、字段类型是什么、允许的操作符有哪些、字段值如何归一化。

```
你需要从用户输入中提取：
1. query：用于语义检索的关键词。
2. filter：只能使用以下字段：
   - year: number，年份
   - source: enum(upload, import, generated)
   - favorited: boolean，是否收藏
   - style: string，风格标签
   - colorTone: enum(warm, cold, neutral)

用户输入："找一些我去年收藏的、暖色调的、赛博朋克城市夜景图"
输出 JSON：{"query":"...", "filter":{...}}
```

**工程要点**：LLM 输出必须做 JSON Schema 校验、字段白名单校验、类型转换和失败兜底，不能直接拼 SQL 或直接信任模型输出。

---

## 五、与普通检索的对比

| 维度 | 普通检索 | 自查询检索 |
|------|----------|------------|
| 查询形式 | 一个文本 → 一个向量或关键词 | 一个文本 → 语义 query + 结构化 filter |
| 过滤方式 | 通常只看相似度 | 相似度召回 + 元数据精确过滤 |
| 用户是否需要懂 Schema | 不需要 | 不需要，由 LLM 映射 |
| 额外 LLM 调用 | 通常不需要 | 通常需要 1 次查询解析调用 |
| 对“去年的文档”处理 | 容易变成语义匹配 | 可转为年份过滤条件 |
| 风险 | 召回可能不满足精确约束 | LLM 可能解析错条件，需要校验和兜底 |

---

## 六、与 Query Rewrite 的区别（易混淆）

| 维度 | Query Rewrite | Self-Query |
|------|---------------|------------|
| 目标 | 把查询改写成更适合召回的文本 | 把查询拆成语义文本和结构化过滤条件 |
| 输出 | 一个优化后的纯文本 query | query + filter / structured query |
| 主要作用 | 提升语义召回率、消除指代、省略补全 | 提升过滤准确性，减少不满足硬条件的候选 |
| 影响方式 | 主要影响召回与排序，是“软约束” | 过滤条件通常是“硬约束” |
| 适用问题 | “它的用法是什么”需要结合上下文改写 | “2024 年发布的 Java 文档”需要字段过滤 |

两者可以组合使用：先 Self-Query 提取过滤条件，再对语义 query 做 Rewrite 或 Expansion。

---

## 七、优缺点与适用边界

| 优点 | 缺点 / 风险 |
|------|-------------|
| 用户自然语言即可，不需要了解数据库字段 | 多一次 LLM 调用，增加延迟和成本 |
| 将语义检索与精确过滤结合，适合带条件查询 | LLM 可能误解析时间、范围、枚举值 |
| Schema 变化时主要更新 Prompt / 配置 | Schema 太大时 Prompt 变长，解析稳定性下降 |
| LangChain、LlamaIndex 等框架已有参考实现 | 不适合字段关系非常复杂或强事务查询的场景 |

**适用场景**：文档、图片、商品、工单、日志、代码片段等带有较丰富元数据，并且用户查询经常包含时间、分类、权限、标签、数值范围等条件。

**不适合场景**：只有少量纯文本、几乎没有元数据，或查询条件必须严格由后端规则系统解析的高风险场景。

---

## 八、面试回答模板

> Self-Query 是 RAG 检索前的查询解析步骤。它让 LLM 把用户自然语言拆成两部分：一部分是用于语义检索的 query，另一部分是用于数据库或向量库过滤的 metadata filter。这样可以解决普通向量检索不擅长处理时间、分类、权限、数值范围这类硬条件的问题。
>
> 工程上我会把可过滤字段、字段类型和允许操作符写进 Prompt，让模型输出结构化 JSON，然后做 Schema 校验、字段白名单校验和兜底。如果解析失败，就退化为普通向量检索或混合检索。Self-Query 和 Query Rewrite 的区别是：Rewrite 主要优化文本召回，Self-Query 会生成过滤条件，通常是硬约束。

---

## 九、参考来源 / 延伸阅读

1. LangChain Documentation, **Self-querying retrievers**. https://python.langchain.com/docs/how_to/self_query/
2. LlamaIndex Documentation, **Metadata Filtering**. https://docs.llamaindex.ai/en/stable/examples/vector_stores/MetadataFilters/
3. Spring AI Reference Documentation, **Retrieval Augmented Generation**. https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html
4. Weaviate Documentation, **Hybrid Search and Filters**. https://weaviate.io/developers/weaviate/search/filters
5. Pinecone Documentation, **Metadata filtering**. https://docs.pinecone.io/guides/search/filter-by-metadata
