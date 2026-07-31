# 如何评估 RAG 系统的效果？检索和生成分别看哪些指标？

> 标签: RAG, 评估, 检索评估, 生成评估 | 难度: 🟡 中等 | 编号: QA22

---

## 一、核心结论

评估 RAG 系统不能只看最终回答是否“看起来正确”，而要把链路拆成三层：

1. **检索评估**：有没有把正确证据召回，并且排在足够靠前的位置；
2. **生成评估**：模型是否基于检索证据生成，答案是否正确、忠实、完整；
3. **端到端评估**：用户任务是否完成，延迟、成本、稳定性和安全性是否可接受。

```text
用户问题
  ↓
检索阶段：Recall@K / MRR / nDCG / Context Precision
  ↓
生成阶段：Faithfulness / Answer Correctness / Citation Accuracy
  ↓
线上阶段：任务成功率 / 延迟 / 成本 / 用户反馈 / 安全合规
```

面试中要强调：**RAG 评估必须分阶段定位问题**。如果答案错了，原因可能是没检到、检到了但排序低、上下文噪声太多，也可能是模型没有忠实使用证据。

---

## 二、为什么要分检索和生成分别评估

RAG 是一个组合系统，错误可能来自不同阶段：

| 错误现象 | 可能原因 | 应看指标 |
|----------|----------|----------|
| 答案完全无关 | 正确文档没有被召回 | Recall@K / Hit Rate |
| 检索到了但没用上 | 正确文档排名太低或上下文过长 | MRR / nDCG / Context Precision |
| 答案有事实错误 | 模型无证据生成或误读上下文 | Faithfulness / Groundedness |
| 答案缺少关键信息 | 召回证据不完整或生成不完整 | Context Recall / Answer Completeness |
| 引用对不上 | 引用编号错误或证据不支撑结论 | Citation Accuracy / Attribution |
| 拒答不稳定 | 空检索保护或置信度判断不可靠 | Refusal Accuracy |

因此，不能只用一个“回答准确率”评价整个 RAG。需要分别评估 Retrieval、Generation 和 End-to-End。

---

## 三、检索阶段看哪些指标

检索阶段的目标是：**把能支撑答案的证据召回，并尽量排在前面**。

### 3.1 Recall@K：正确证据是否进入 Top-K

Recall@K 衡量标准证据是否出现在前 K 个检索结果中。

```text
问题 Q 的标准证据是 doc_8
检索 Top-5 = [doc_2, doc_8, doc_3, doc_1, doc_9]
=> Recall@5 = 命中
```

RAG 中 Recall@K 通常比 Precision@K 更关键，因为如果正确证据没有被召回，生成阶段基本无法补救。

适用场景：

- 评估 chunk_size、overlap、Embedding 模型、向量库参数；
- 比较向量检索、BM25、混合检索、多路召回效果；
- 判断 Top-K 设置是否过小。

### 3.2 Precision@K：Top-K 中有多少是真相关

Precision@K 衡量前 K 个结果中相关文档比例。

```text
Top-5 中有 3 个相关文档
=> Precision@5 = 3 / 5 = 0.6
```

RAG 中 Precision@K 反映上下文噪声。如果 Precision 很低，即使 Recall 高，也会把大量无关内容注入 Prompt，增加生成错误和上下文浪费。

### 3.3 MRR：第一个正确结果排得多靠前

MRR（Mean Reciprocal Rank）关注第一个相关结果的位置。

```text
第一个相关文档排第 2 位 => RR = 1/2
第一个相关文档排第 5 位 => RR = 1/5
```

MRR 越高，说明正确证据越靠前。它适合评估“用户问题通常只需要一个核心证据”的场景。

### 3.4 nDCG：排序质量是否合理

nDCG（Normalized Discounted Cumulative Gain）考虑相关性等级和排名位置。相关性高的文档排在前面，得分更高。

适用场景：

- 一个问题可能有多个相关文档；
- 文档相关性有强弱等级；
- 需要比较 Rerank、RRF、混合检索融合策略。

### 3.5 Hit Rate：是否至少命中一个正确证据

Hit Rate 衡量 Top-K 中是否至少有一个相关结果。它比 Recall@K 更粗，但易解释，适合做线上监控或业务报表。

### 3.6 Context Precision / Context Recall

这是 RAG 更贴近生成阶段的检索指标：

| 指标 | 含义 |
|------|------|
| Context Precision | 注入上下文中有多少内容真正相关 |
| Context Recall | 回答所需信息有多少被上下文覆盖 |

它们比传统 IR 指标更贴近 RAG，因为最终影响模型的是“注入 Prompt 的上下文”，而不是检索列表本身。

---

## 四、生成阶段看哪些指标

生成阶段的目标是：**在检索证据范围内生成正确、完整、忠实、可引用的答案**。

### 4.1 Faithfulness / Groundedness：答案是否忠实于证据

Faithfulness 衡量答案中的事实断言是否能被检索上下文支持。

```text
检索上下文只说：“Spring AI 支持 ChatClient Advisor。”
模型回答：“Spring AI 的 Advisor 支持 ChatClient，并且默认集成了某某商业插件。”
=> 后半句没有证据支撑，Faithfulness 降低
```

这是 RAG 生成评估中非常核心的指标，因为 RAG 的价值在于 grounded generation，而不是让模型自由发挥。

### 4.2 Answer Correctness：答案是否正确

Answer Correctness 衡量答案与标准答案或人工标注答案的一致性。它可以通过人工评审、规则匹配或 LLM-as-Judge 评估。

注意：答案正确不等于忠实。模型可能凭自身参数知识答对，但没有使用检索证据；在企业知识库场景，这仍然可能不合格。

### 4.3 Answer Relevancy：答案是否回应用户问题

Answer Relevancy 衡量答案是否围绕用户问题展开，而不是答非所问或输出大量无关背景。

常见问题：

- 检索上下文很多，模型泛泛总结；
- 用户问实现方法，模型只解释概念；
- 用户问某个版本，模型回答通用版本。

### 4.4 Completeness：答案是否覆盖关键点

Completeness 衡量答案是否遗漏重要信息。对于多步骤问题、对比题、方案设计题尤其重要。

例如用户问“检索和生成分别看哪些指标”，只回答 Recall@K 和 Faithfulness 就不完整，还应补充排序、引用、端到端指标等。

### 4.5 Citation Accuracy：引用是否正确

如果系统要求引用来源，需要评估：

- 引用编号是否存在；
- 引用内容是否支撑对应句子；
- 引用是否覆盖关键断言；
- 是否存在伪造来源或错配来源。

在高可信 RAG 中，Citation Accuracy 和 Faithfulness 一样重要。

### 4.6 Refusal Accuracy：该拒答时是否拒答

RAG 系统不应在缺少证据时强行回答。需要评估：

| 场景 | 正确行为 |
|------|----------|
| 知识库没有相关资料 | 拒答或提示无法基于当前知识库确定 |
| 检索结果低于阈值 | 拒答或要求用户补充信息 |
| 用户问越权内容 | 拒答并说明权限不足 |
| 资料存在冲突 | 明确说明冲突，而不是合成确定结论 |

---

## 五、端到端和线上指标

离线指标只能说明系统在评测集上的质量，线上还要看真实用户体验和工程成本。

| 指标 | 说明 |
|------|------|
| 任务成功率 | 用户是否完成查询、决策、操作等目标 |
| 用户满意度 / 点赞点踩 | 用户主观反馈 |
| 追问率 | 用户是否频繁追问“你确定吗”“来源呢” |
| 引用点击率 | 用户是否点击来源验证答案 |
| 平均延迟 / P95 / P99 | 检索、Rerank、生成整体耗时 |
| Token 成本 | Prompt 上下文和生成输出成本 |
| 缓存命中率 | 高频问题是否能降低延迟和成本 |
| 安全违规率 | 是否输出越权、隐私、敏感或不合规内容 |

工程上通常会把离线评测、线上 A/B 测试和人工抽检结合起来，而不是只依赖单一指标。

---

## 六、主流评估流程

### 6.1 构建评测集

评测集至少包含：

```text
query: 用户问题
gold_contexts: 标准证据 chunk / 文档
gold_answer: 标准答案或人工参考答案
expected_citations: 期望引用来源
negative_cases: 应该拒答的问题
```

评测集应覆盖：

- 高频问题；
- 长尾问题；
- 多跳问题；
- 无答案问题；
- 权限边界问题；
- 容易混淆的相似问题。

### 6.2 分阶段评估

```text
Step 1: 只评估 Retriever
  - Recall@K / MRR / nDCG / Context Precision

Step 2: 固定检索结果，评估 Generator
  - Faithfulness / Correctness / Relevancy / Citation Accuracy

Step 3: 端到端评估完整链路
  - Answer Quality / Latency / Cost / Refusal Accuracy
```

这种拆分可以定位问题来源：是检索器没召回，还是生成器没有正确使用上下文。

### 6.3 回归测试

每次修改以下内容后都应跑评测集：

- chunk_size / overlap；
- Embedding 模型；
- Top-K；
- 混合检索权重；
- Rerank 模型；
- Prompt 模板；
- 上下文压缩策略；
- 拒答阈值。

RAG 系统的评估不是一次性工作，而是持续回归体系。

---

## 七、常用工具和框架

| 工具 / 框架 | 主要能力 | 适用场景 |
|-------------|----------|----------|
| RAGAS | Context Precision、Context Recall、Faithfulness、Answer Relevancy 等 RAG 指标 | 快速搭建自动化 RAG 评估 |
| DeepEval | LLM 应用评估、RAG 指标、单元测试风格评估 | CI / 回归测试 |
| LangSmith | 数据集、trace、评估器、线上反馈 | LangChain / LangGraph 生态 |
| LlamaIndex Evaluation | Faithfulness、Relevancy、Retriever eval | LlamaIndex 生态 |
| TruLens | Groundedness、上下文相关性、反馈函数 | RAG 可观测性和解释 |
| 人工评审 | 事实正确性、业务可用性、合规性 | 高风险场景不可替代 |

自动评估适合规模化回归，但高风险业务仍需要人工抽检和专家标注。

---

## 八、常见误区

| 误区 | 更准确的说法 |
|------|--------------|
| 只看最终答案准确率就够了 | RAG 要分别评估检索、生成和端到端体验 |
| Recall@K 越高越好 | 召回高但噪声大，会降低 Context Precision 和生成质量 |
| 检索命中就代表答案正确 | 模型可能忽略、误读或扩展检索内容 |
| LLM-as-Judge 完全可靠 | LLM 评估有偏差，应配合人工标注、规则和抽检 |
| 评测集做一次即可 | 知识库、模型、Prompt、索引参数变化后都需要回归评估 |
| 无答案问题不重要 | 拒答准确率是控制幻觉和越权回答的重要指标 |

---

## 九、面试回答模板

> RAG 系统评估要拆成检索、生成和端到端三层。检索阶段主要看正确证据有没有被召回，以及排序是否靠前，常用指标包括 Recall@K、Precision@K、MRR、nDCG、Hit Rate，以及更贴近 RAG 的 Context Precision 和 Context Recall。生成阶段要看模型是否基于证据回答，常用指标包括 Faithfulness 或 Groundedness、Answer Correctness、Answer Relevancy、Completeness、Citation Accuracy 和 Refusal Accuracy。端到端还要看任务成功率、用户反馈、引用点击率、延迟、P95/P99、token 成本和安全违规率。工程上我会先构建包含 query、标准证据、标准答案、期望引用和无答案样例的评测集，然后分阶段评估 Retriever、Generator 和完整链路。这样当答案出错时，可以定位是召回问题、排序问题、上下文噪声问题，还是生成阶段没有忠实使用证据。

---

## 十、参考来源 / 延伸阅读

1. RAGAS Documentation: RAG evaluation metrics. https://docs.ragas.io/
2. LangSmith Evaluation Documentation. https://docs.smith.langchain.com/evaluation
3. LlamaIndex Evaluation Documentation. https://docs.llamaindex.ai/en/stable/module_guides/evaluating/
4. DeepEval Documentation: RAG metrics and LLM evaluation. https://docs.confident-ai.com/
5. TruLens Documentation: RAG Triad and groundedness evaluation. https://www.trulens.org/
6. Lewis, P. et al. *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*. NeurIPS, 2020. https://arxiv.org/abs/2005.11401
