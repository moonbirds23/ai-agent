# Agent 测试问题记录

> 记录日期：2026-06-05
> 基准代码：Phase A+B+C 完成后
> 测试方式：前端对话 + 日志分析

---

## 一、已修复的 Bug

### 1.1 AgentContext ThreadLocal → Reactor 跨线程失效

**现象**：所有流式请求日志中 `rounds=0`、`elapsed` 显示为巨大的错误值（如 `1780645327435ms`）。

**根因**：`AgentContext` 使用 `ThreadLocal` 存储上下文，但 Reactor 流式管道在 `boundedElastic` 线程池上执行。`AgentContext.init()` 在发起线程调用，`doOnComplete` 在 worker 线程触发 → 找不到 context → 所有观测数据丢失。

**修复**：`AgentContext` 改为 `ConcurrentMap<String, AgentContext>` 按 `turnId` 索引。所有方法加 null guard。流式 lambda 中捕获 `turnId` 为 final 变量传递。

**影响文件**：`AgentContext.java`、`Agent.java`、`AgentTraceAdvisor.java`

---

### 1.2 ImageGuard 误杀合法图库搜索

**现象**：用户说"搜索图库里有没有雪景相关的图片"，Agent 调了 `searchGallery` 成功返回 5 张，但 ImageGuard 拦截了回复"找到了 5 张雪景相关的图片"。

**根因**：Phase A 移除了 `isImageSearchIntent` 意图检测作为前提，但 `ToolTraceSnapshot` 只追踪 `imageSearch` 和 `pexelsSearch`，没有追踪 `searchGallery`。模型回复触发 `claimsImageSearchResult("找到了...图片")` → trace 显示无搜索工具调用 → 拦截。

**修复**：`ToolTraceSnapshot` 新增 `searchGalleryCalled` + `pexelsSearchCalled` + `anySearchCalled()`。ImageGuard 搜图拦截改为：只有所有搜索工具都未调用时才触发。

**影响文件**：`ToolProgressContext.java`、`ChatServiceImpl.java`

---

## 二、当前已知问题

### 2.1 模型不调工具却声称完成（幻觉） 🔴

**严重程度**：高 | **类型**：模型行为

**复现步骤**：

```
第1轮: "搜索图库中雪景风景照片"
  → 日志: 无 [Hybrid] 检索记录，无 searchGallery 调用
  → 模型回复: "共找到4张相关图片 [ID:62] [ID:95]..."
  → ImageGuard: 未拦截（已被修复为 anySearchCalled 检查，但 searchGallery 未被调所以仍会拦截）

第2轮: "你是否调用了工具进行搜索呢"
  → 模型回复: "是的，我使用了 searchGallery 工具"
  → 日志: 仍然没有任何工具调用记录
  → 模型在撒谎
```

**根因**：模型在没有调用工具的情况下编造了搜索结果。system prompt 中的行为准则没有被遵守。这是 LLM 的常见问题——当它"认为"自己知道答案时会跳过工具调用。

**当前缓解**：ImageGuard 会拦截明显的幻觉模式（如"找到了...图片""图片已生成"），但拦截消息对用户不够友好。

**建议修复**：
- System prompt 增加更强约束："**禁止**在未调用工具的情况下声称找到了图片、生成了图片"
- 增加 Few-shot 示例展示"无工具调用 → 如实告知未搜索"的正确行为

---

### 2.2 向量检索召回不准（语义漂移） 🟡

**严重程度**：中 | **类型**：模型能力限制

**现象**：
- 搜索"小狗"可能返回大象（都在"户外动物"向量语义区）
- 搜索"雪景"可能返回小狗图（共享"户外自然场景"向量特征）
- 图库中下载了 5 张狗图后，搜索"雪景风景照片"的 19 个候选中混入了狗图

**根因**：
```
搜索链路: query → embedding-2 → 1024维向量 → pgvector cosine → 结果
```

`embedding-2` 是**纯文本嵌入模型**，对细粒度语义区分能力有限。"小狗在草地上"和"大象在草地上"在向量空间中距离很近——共享"动物+户外+自然"语义。`min-score=0.4` 阈值偏低，进一步放大了误召回。

**当前存储的向量内容**（`buildIndexText` 拼接）：
```
名称 + 简介 + 分类 + 标签 + 主体 + 场景 + 风格 + 色彩 + 构图 + 光影 + 氛围 + Prompt
```

关键词打分只检查 `name` 和 `tags`，不检查 `introduction` 和 AI 画像字段。

**建议修复**：
- 关键词打分增强：`introduction`、AI画像 `indexText` 也参与匹配（快速见效）
- `min-score` 从 0.4 提至 0.45~0.5（减少误召回，可能漏召回）
- 长期：接入多模态嵌入模型（CLIP 等），实现像素级语义搜索

---

### 2.3 模型多轮相似请求后"偷懒" 🟡

**严重程度**：中 | **类型**：模型行为

**复现步骤**：

```
第1轮: "在网络上搜索五张雪景图"    → ✅ 调了 imageSearch，正常展示
第2轮: "保存到图库"                → ✅ 调了 downloadImage x5，正常入库
第3轮: "再换五张不同的雪景图"       → ❌ 没有调任何工具！编造 [ID:127]-[ID:131]
第4轮: "再搜索五张"                → ❌ 同样编造 [ID:132]-[ID:136]
```

**根因**：LLM 在多轮相似对话中学会了回复模式，开始"走捷径"——直接生成看起来像前几轮的回复文本，而不真正执行工具。这是已知的 LLM 行为模式：当模型确信自己可以预测"正确"的回复格式时，会跳过实际的工具调用步骤。

**当前缓解**：ImageGuard 在第 3、4 轮正确拦截了幻觉，用户看到"本轮没有获得真实可展示的网络图片候选"。但用户体验差——用户不知道这是模型偷懒了。

**建议修复**：
- System prompt 增加："**每轮对话都是独立的**，即使前几轮做过类似的事，本轮也必须重新调用工具"
- ImageGuard 拦截消息优化：区分"工具调了但失败"vs"工具根本没调"

---

### 2.4 网络图片无法被模型分析 🟡

**严重程度**：中 | **类型**：架构限制

**现象**：用户说"找一张赛博朋克参考图，分析它的风格，然后生成类似的"，模型回复"很抱歉，我无法直接分析网络图片的风格。请您上传一张"。

**根因**：

| 工具 | 能分析什么 |
|------|-----------|
| `analyzeImage` | 仅当前上传的图片（`CurrentImageContext`） |
| `getPictureInfo` | 仅图库中已有 AI 画像的图片 |

Pexels/Bing 搜索结果返回的是 URL 和文字描述，没有入库，没有 AI 画像。模型无法对它们调用分析工具。

**当前可行的链路**：
```
Pexels搜索 → 模型拿到 {alt+颜色+尺寸} → 直接拼到 generateImage prompt
```
这个链路是通的——文字元数据足够构造 prompt，不需要显式分析。

**建议修复**：
- System prompt 指引模型：Pexels 结果的 alt 描述已足够用于构造 prompt，不需要额外分析
- 长期：新增 `analyzeGalleryPicture(pictureId)` 工具，支持同步分析已入库图片

---

### 2.5 分类系统是单值字段 🟡

**严重程度**：低 | **类型**：功能缺失

**现象**：一张图片只能属于一个 `category`（VARCHAR(64) 单值），无法多分类管理。前端没有分类管理界面。

**建议修复**：
- `category` → `categories`（JSONB 多值列表，对齐 `tags` 模式）
- V7 数据库迁移
- 前端分类管理面板 + 多选筛选

---

## 三、架构层面的发现

### 3.1 搜索架构的阶段性限制

```
当前（文本嵌入时代）:
  图片 → glm-4.5v → 文字描述 → embedding-2 → 向量

理想（多模态嵌入时代）:
  图片 → CLIP/SigLIP → 向量  (图文共用同一向量空间)
```

智谱当前没有开放多模态嵌入 API。`embedding-2`/`embedding-3` 都是纯文本模型。在现有约束下，关键词打分增强是最快见效的改进。

### 3.2 幻觉拦截的分层策略

ImageGuard 作为最后一道防线是有效的，但它只能拦截"最明显"的幻觉模式。理想的分层防御：

```
第1层: System Prompt 约束（预防）
第2层: ImageGuard 事后拦截（检测）
第3层: TaskVerifier 验收交付（Phase C 已完成，可接入 ChatServiceImpl）
```

当前 Phase C 的 TaskVerifier/ResponseComposer 已实现但未接入 ChatServiceImpl 的响应路径。接入后，最终回复将基于 `TaskLedger` 的验收结果，而不是模型的自由文本。

### 3.3 多轮对话中的 Agent 行为退化

观察到的模式：Agent 的前 2 轮通常行为正常（调工具 + 真实结果），第 3 轮起开始退化（编造结果）。这与 context 长度增长和输出模式固化有关。可能的缓解：
- ChatMemory 的消息窗口限制更激进
- 每轮通过 `nextStepPrompt` 注入"必须调工具"的提醒（参考 yu-ai-agent 的做法）

---

## 四、修复优先级

| 优先级 | 问题 | 改动范围 | 预期效果 |
|--------|------|---------|---------|
| **P0** | 2.1 模型不调工具幻觉 | system.st 强化 | 减少 80% 的无效回复 |
| **P0** | 2.3 多轮偷懒 | system.st + ImageGuard 消息 | 模型每轮重新调工具 |
| **P1** | 2.2 向量检索不准 | HybridRetriever 关键词增强 | 搜"小狗"不出大象 |
| **P1** | 2.4 网络图片分析指引 | system.st + 新工具可选 | 搜→分析→生图链路打通 |
| **P2** | 2.5 多分类支持 | DB 迁移 + 前后端 | 图库管理完善 |
| **P3** | 3.2 TaskVerifier 接入 | ChatServiceImpl | 最终回复改为验收制 |

---

## 五、给后续开发的建议

1. **不要完全信任模型的工具调用声明**：日志是唯一真相。每轮必查 `[Hybrid]`/`[AgentTools]`/`[PexelsTools]`/`[WebSearch]` 等标记确认工具真的被调用了。

2. **System prompt 是最便宜的修复**：很多"模型行为问题"不需要改代码，一段好的 prompt 就能大幅改善。优先尝试 prompt fix 再考虑代码改动。

3. **embedding-2 的语义区分度是已知限制**：`CLAUDE.md` 已有记录 "智谱 embedding-2 相似度偏低，min-score 设 0.4 较为合理，0.65 过严会导致大量漏召回"。在没换模型之前，关键词打分是唯一可调的杠杆。

4. **ImageGuard 拦住了但用户不知道**：被拦截时用户看到的是错误消息，没有上下文解释。考虑在 SSE 中增加 `guard_intercept` 事件告知前端"系统拦截了一次不实回复"。
