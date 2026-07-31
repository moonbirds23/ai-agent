# RAG 中文档切割的 chunk_size 和 overlap 应该如何设置？

> 标签: RAG, 分块策略, Chunking, 索引优化 | 难度: 🟡 中等 | 编号: QA21

---

## 一、核心结论

`chunk_size` 和 `chunk_overlap` 没有通用最优值，合理设置取决于**文档类型、Embedding 模型输入长度、检索粒度、上下文窗口预算和评测指标**。工程上通常先采用一个主流基线，再通过离线评测调参。

常见经验值：

| 参数 | 常见起点 | 说明 |
|------|----------|------|
| `chunk_size` | 300~800 tokens | 通用知识库、技术文档常用起点 |
| `chunk_overlap` | chunk_size 的 10%~20% | 用于缓解语义被切断的问题 |
| 最终注入 Top-K | 3~8 个 chunk | 受上下文窗口和相关性约束 |

如果是中文场景，不能简单按字符数等价换算 token，最好使用实际 tokenizer 或框架内置 token splitter 统计。

---

## 二、chunk_size 影响什么

`chunk_size` 指单个文档片段的最大长度，通常按 token 或字符计算。它直接影响召回精度、语义完整性、索引规模和生成质量。

### 2.1 chunk 太小的问题

chunk 太小时，单个片段语义不完整，容易丢失上下文。

```text
Chunk A: “Spring AI 的 Advisor 可以在 ChatClient 调用前后……”
Chunk B: “……拦截请求并修改上下文。”
```

如果检索只命中 Chunk A，模型可能看不到完整结论。

常见风险：

- 语义碎片化，答案缺少上下文；
- 同一知识点分散在多个 chunk 中，召回不稳定；
- 索引条目数量增多，存储和检索成本上升；
- Top-K 中容易出现多个相邻碎片，占用上下文窗口。

### 2.2 chunk 太大的问题

chunk 太大时，一个向量要表示过多信息，检索粒度变粗。

常见风险：

- Embedding 表达被多个主题稀释，相关性下降；
- 检索命中后带入大量无关内容，增加上下文噪声；
- Rerank 和 Prompt 注入成本升高；
- LLM 更容易忽略中间关键信息，出现 “Lost in the Middle” 问题。

### 2.3 经验判断

| 文档类型 | chunk_size 起点 | 原因 |
|----------|----------------|------|
| FAQ / 问答对 | 100~300 tokens | 单条知识短，天然自包含 |
| 技术文档 / Markdown | 300~800 tokens | 需要保留标题、段落和局部上下文 |
| 法规 / 制度 / 合同 | 500~1000 tokens | 条款依赖上下文，切太碎容易误解 |
| 代码文档 | 按函数 / 类 / 配置块切分 | 结构边界比固定 token 更重要 |
| 表格 / 结构化数据 | 按行组、表头+行块切分 | 需要保留列名和单元格关系 |

这些值是调参起点，不是固定标准。最终应通过检索评估和答案质量评估确认。

---

## 三、overlap 影响什么

`chunk_overlap` 指相邻 chunk 之间重复保留的一段内容，主要用于避免关键信息刚好落在切分边界。

```text
无 overlap：
Chunk 1: RAG 的核心流程包括文档解析、分块、向量化
Chunk 2: 存储、检索、Prompt 拼接和生成

有 overlap：
Chunk 1: RAG 的核心流程包括文档解析、分块、向量化
Chunk 2: 分块、向量化、存储、检索、Prompt 拼接和生成
```

### 3.1 overlap 太小

- 边界处语义可能断裂；
- 跨段落概念、长句、定义和解释容易分离；
- 检索命中一个 chunk 时可能缺少前后文。

### 3.2 overlap 太大

- 索引冗余增加；
- 多个高度相似 chunk 同时命中，降低结果多样性；
- 向量库和全文索引存储成本上升；
- Top-K 容易被重复内容占满。

### 3.3 常见设置

工程上常以 `chunk_size` 的 10%~20% 作为起点：

```text
chunk_size = 500 tokens
chunk_overlap = 50~100 tokens
```

如果文档结构清晰、按标题和段落切分，overlap 可以较小；如果文档长句多、段落依赖强，overlap 可以适当增大。

---

## 四、主流设置方法

### 4.1 先基于文档结构切分，再用 token 限制兜底

生产中不建议只做固定长度切分。更稳妥的方式是：

```text
标题 / 章节 / 段落优先
  ↓
超过 chunk_size 的片段再递归按句子、标点切分
  ↓
必要时添加 overlap
  ↓
保留标题路径和 metadata
```

也就是常见的 Recursive / Structure-aware Chunking。

### 4.2 保留标题路径和元数据

对于技术文档，chunk 内容最好带上标题层级：

```text
文档标题: Spring AI Reference
章节路径: ChatClient > Advisors > MessageChatMemoryAdvisor
正文: ...
```

这样即使正文片段较短，Embedding 仍能获得足够语义上下文，也便于引用溯源。

### 4.3 根据 Embedding 模型约束设置上限

`chunk_size` 不能超过 Embedding 模型的最大输入长度。即使模型支持较长输入，也不代表 chunk 越长越好，因为检索需要的是高区分度语义表示。

更专业的做法是：

- 以模型 token 限制作为硬上限；
- 以检索效果和上下文预算作为实际上限；
- 对长文档通过层级索引、父子 chunk 或摘要索引增强，而不是无限增大 chunk。

---

## 五、如何通过评测调参

chunk 参数不应只靠经验设置，最好用离线评测闭环。

### 5.1 建立评测集

准备一批真实问题和标准证据：

```text
问题: “Spring AI 中 Advisor 的作用是什么？”
标准证据: spring-ai-reference.md#advisor-section
```

### 5.2 对比多组参数

```text
A: chunk_size=300, overlap=50
B: chunk_size=500, overlap=80
C: chunk_size=800, overlap=120
D: structure-aware + chunk_size=600, overlap=80
```

### 5.3 观察指标

| 指标 | 说明 |
|------|------|
| Recall@K | 标准证据是否进入 Top-K |
| MRR | 标准证据排名是否靠前 |
| nDCG | 排序质量是否合理 |
| Context Precision | 注入上下文中相关内容占比 |
| Faithfulness | 答案是否忠实于检索上下文 |
| Token Cost | 每次请求注入上下文成本 |

一般目标不是单纯追求 Recall@K 最高，而是在召回率、上下文噪声、成本和答案质量之间取得平衡。

---

## 六、常见推荐配置

### 6.1 通用知识库

```text
chunk_size: 400~600 tokens
chunk_overlap: 50~100 tokens
切分方式: 递归分块 + 段落/句子边界优先
```

适合内部文档、教程、产品说明等多数场景。

### 6.2 技术文档 / Markdown

```text
chunk_size: 500~800 tokens
chunk_overlap: 80~120 tokens
切分方式: 标题层级优先 + 递归分块
metadata: title_path, section, source_url
```

技术文档通常有章节结构，标题路径对检索很重要。

### 6.3 FAQ / 问答库

```text
chunk_size: 单个 QA 对为一个 chunk
chunk_overlap: 通常不需要
切分方式: 按问答对结构切分
```

FAQ 本身是天然的语义单元，不应强行按固定 token 切碎。

### 6.4 法规 / 合同 / 政策

```text
chunk_size: 800~1200 tokens
chunk_overlap: 100~200 tokens
切分方式: 条款 / 章节优先
增强: 引用页码、条款号、上下文窗口
```

这类文档上下文依赖强，切分时需要保留条款层级和前后文。

### 6.5 代码和 API 文档

```text
chunk_size: 不以固定 token 为主
切分方式: 函数、类、接口、配置块、代码注释边界
metadata: language, symbol_name, file_path, section
```

代码类资料更适合结构化切分，固定 token 只是兜底。

---

## 七、工程落地要点

1. **优先按语义结构切分**：标题、段落、条款、函数边界通常比固定 token 更重要。
2. **chunk_size 是召回粒度，不只是存储参数**：它决定一个向量代表多大语义范围。
3. **overlap 是边界补偿，不是越大越好**：过大会造成重复召回和索引膨胀。
4. **保留 metadata**：标题路径、页码、章节、来源 URL 能显著提升检索和引用质量。
5. **配合 Rerank 和 MMR**：如果 overlap 带来重复候选，需要在后处理阶段去冗余。
6. **按业务评测调参**：不同语料、模型和检索器下，经验值只能作为起点。
7. **区分字符和 token**：中文场景尤其要注意 tokenizer 差异，避免按字符估算导致超长或过短。

---

## 八、常见误区

| 误区 | 更准确的说法 |
|------|--------------|
| chunk_size 越大越好 | 太大会降低检索粒度，并引入上下文噪声 |
| chunk_size 越小越准 | 太小会破坏语义完整性，导致答案缺上下文 |
| overlap 越大越安全 | 过大导致索引冗余、重复召回和上下文浪费 |
| 所有文档统一一个参数 | 不同文档类型应采用不同切分策略和参数 |
| 只看检索命中，不看生成质量 | RAG 应同时评估召回、排序、上下文质量和答案忠实度 |
| 按字符数就足够 | 模型按 token 处理输入，最好以 token 预算为准 |

---

## 九、面试回答模板

> RAG 中 chunk_size 和 overlap 没有固定最优值，它们本质上是在检索粒度和上下文完整性之间做权衡。chunk 太小会导致语义碎片化，模型拿不到完整证据；chunk 太大则会让一个向量承载多个主题，降低检索区分度，并把更多无关内容带入 Prompt。工程上通常先以 300~800 tokens 作为 chunk_size 起点，overlap 设置为 10%~20%，再根据文档类型调整。例如 FAQ 可以按单个问答对切，技术文档适合按标题和段落递归切分，法规合同可以适当增大 chunk 并保留条款层级。最终参数不应该只靠经验，而要通过 Recall@K、MRR、Context Precision、Faithfulness 和 token cost 等指标评估。我的默认方案是结构优先切分，加 token 上限兜底，再用少量 overlap 处理边界问题，并配合 Rerank、MMR 和上下文压缩控制最终注入质量。

---

## 十、和 QA5 的关系

QA5 主要回答“有哪些分块策略”，包括固定分块、递归分块、语义分块、结构化分块等；本题更关注具体参数调优，即 `chunk_size` 和 `chunk_overlap` 如何设置、如何评估、如何按文档类型调整。

---

## 十一、参考来源 / 延伸阅读

1. LangChain Text Splitters 文档：https://python.langchain.com/docs/concepts/text_splitters/
2. LangChain RecursiveCharacterTextSplitter 文档：https://python.langchain.com/docs/how_to/recursive_text_splitter/
3. LlamaIndex Node Parser / Text Splitter 文档：https://docs.llamaindex.ai/en/stable/module_guides/loading/node_parsers/
4. Liu, N. F. et al. *Lost in the Middle: How Language Models Use Long Contexts*. TACL, 2024. https://arxiv.org/abs/2307.03172
5. Lewis, P. et al. *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*. NeurIPS, 2020. https://arxiv.org/abs/2005.11401
6. Spring AI ETL Pipeline / Document Processing 文档：https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html
