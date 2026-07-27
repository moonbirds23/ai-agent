# AI Agent：RAG 评价体系与 MCP Server/Client 引入任务书

> 本文档用于交接给本地 AI 分阶段执行。执行者必须严格遵守阶段边界、验收标准和停止条件，不得跨阶段重构。

## 0. 项目基准信息

- GitHub 仓库：`https://github.com/moonbirds23/ai-agent`
- 分析分支：`master`
- 分析提交：`c0d4cf60bdd01d22ce67c4178b291d76af1180d9`
- 技术栈：Java 21、Spring Boot 3.5.14、Spring AI 1.0.0、PostgreSQL、pgvector、Redis、Flyway
- 当前项目：云图库 AI Agent，具有对话、图片生成、图片分析、图库管理、Pexels 搜索、三层 RAG 和 Agent Workflow 能力
- 本任务目标：
  1. 建立适合小规模图片图库 RAG 的评价与回归体系；
  2. 将 Pexels、图库候选检索等边界清晰的能力抽为独立 MCP Server；
  3. 让主 Agent 作为 MCP Client 使用这些能力；
  4. 使用同一套 RAG 评测集证明 MCP 改造没有破坏原有检索效果。

## 1. 最高优先级执行规则

执行者必须遵守以下规则：

1. 每次只执行本文中的一个阶段，不得跨阶段修改。
2. 开始每个阶段前，先列出准备修改和新增的文件。
3. 完成每个阶段后，必须报告：
   - 变更摘要；
   - 修改和新增的文件；
   - 执行的测试命令；
   - 测试结果；
   - 未验证内容；
   - 是否偏离本任务书。
4. 不允许顺手调整无关包名、目录名、错误码、接口、前端样式或日志格式。
5. 不得修改现有 REST API 路径、请求字段、响应字段和 SSE 事件类型。
6. 不得修改现有对外 `@Tool` 名称，除非本文明确要求。
7. RAG 基线建立之前，不得调整：
   - `top-k`；
   - `min-score`；
   - vector/keyword/metadata 权重；
   - Query Rewrite Prompt；
   - rerank 规则；
   - 向量模型和维度。
8. MCP Server 验收之前，不得删除当前本地 Pexels 和图库检索实现。
9. 不得通过删除测试、降低断言、添加无条件跳过来处理测试失败。
10. 如果阶段验收测试失败，立即停止并报告，不得继续下一阶段。
11. 所有密钥只能来自环境变量，禁止写入代码、测试数据、日志和 Git。
12. 所有 MCP 长期契约必须使用明确 DTO/record，不得将 `Map<String, Object>` 作为稳定接口模型。

## 2. 当前代码链路说明

### 2.1 当前 RAG 主链路

当前生产链路为：

```text
ChatServiceImpl
  → RagServiceImpl.buildContext()
      → RagQueryRewriteService.rewrite()
      → HybridGalleryRetriever.retrieve()
      → RagReranker.rerank()
      → RagContextPacker.pack()
  → PromptReferenceAssembler.assemble()
```

当前已经能够获取：

- 原始 Query；
- rewrittenQuery；
- RagSearchCriteria；
- candidates；
- selected；
- resolvedReferenceMode；
- selectedSummary；
- RAG 总延迟。

当前不足：

1. `RagTraceServiceImpl` 只输出日志，没有形成离线评测报告。
2. 现有 RAG 测试主要验证组件是否被调用，没有评价真实图片 ID 是否命中。
3. 当前 `HybridGalleryRetrieverImpl` 并非严格意义上的向量结果与关键词结果并集：
   - 正常情况下先做向量检索；
   - 关键词分数和元数据分数只在向量候选内部计算；
   - 只有向量检索为空或异常时，才执行 SQL 关键词降级。
4. `GalleryServiceImpl.search()` 使用 retriever 原始返回顺序，没有经过 `RagReranker`；自动 RAG 链路会经过 reranker，因此两条搜索路径可能存在排序差异。

第 3、4 点在建立基线前不得修改。先通过评价体系测量，再单独立项优化。

### 2.2 当前 Agent 工具调用存在两条路径

自动路径：

```text
SpringAiAutoToolExecutor
  → ChatClient.defaultTools(
      GalleryAgentTools,
      WebSearchTools,
      PexelsSearchTools
    )
```

手动工作流路径：

```text
ManualReactExecutor
  → BackendToolExecutor
      → switch(toolName)
          → GalleryService / PexelsPhotoService / 其他服务
```

MCP 改造必须同时覆盖这两条路径，否则会出现简单请求走 MCP、复杂工作流仍走本地调用的问题。

## 3. 总体实施顺序

必须按以下顺序实施：

1. 冻结图库语料快照。
2. 实现确定性的 RAG 指标计算器。
3. 实现 RAG Evaluation Runner。
4. 完成人工标注评测集。
5. 运行本地检索并生成第一份 baseline。
6. 定义 MCP JSON 契约。
7. 创建独立 Image Retrieval MCP Server。
8. 实现 Pexels MCP 工具。
9. 实现图库候选召回 MCP 工具。
10. 主应用接入 MCP Client Gateway。
11. 迁移 Pexels 调用。
12. 迁移图库候选召回。
13. 分别以 local 和 mcp 模式运行同一评测集。
14. 等价性验收通过后，再决定是否删除生产环境的本地实现。

---

# 第一部分：RAG 评价体系

## R0. 冻结图库语料快照

### R0.1 新增目录

```text
src/test/resources/rag-eval/gallery-v1/
├── corpus-manifest.json
├── cases.jsonl
├── annotation-guide.md
└── baseline.json
```

第一阶段只创建结构和说明时，`cases.jsonl` 和 `baseline.json` 可以先为空，但格式必须确定。

### R0.2 corpus-manifest.json 格式

最低字段：

```json
{
  "datasetVersion": "gallery-v1",
  "createdAt": "2026-07-10",
  "embeddingModel": "embedding-2",
  "embeddingDimensions": 1024,
  "indexTextVersion": "v1",
  "pictureCount": 0,
  "pictures": [
    {
      "pictureId": 12,
      "picHash": "sha256...",
      "name": "水墨山水",
      "vectorStatus": 1
    }
  ]
}
```

### R0.3 标识规则

评测标注不能只保存数据库自增 ID，必须同时保存：

- `pictureId`：方便人工查看和调试；
- `picHash`：作为图片稳定身份；
- `name`：仅用于报告展示，不作为身份判断依据。

评测开始前必须校验：

1. 所有标注 `picHash` 都能在当前图库解析；
2. `pictureId` 与 `picHash` 指向同一图片；
3. `vectorStatus` 可用；
4. 缺失图片时终止评测，不能将缺失图片计为检索失败。

### R0.4 R0 验收

- 目录和 JSON 格式确定；
- 能读取图库并生成 corpus manifest；
- manifest 中没有重复 `picHash`；
- 普通 `mvn test` 不受影响。

## R1. 人工评测集设计

### R1.1 第一版规模

第一版建立 40～60 条 Query，建议分布如下：

| Query 类型 | 建议数量 | 说明 |
|---|---:|---|
| 已知图片/精确需求 | 6～8 | 明确期望某张或某几张图片 |
| 主体语义 | 8～10 | 城市、人物、动物、建筑等 |
| 风格 | 6～8 | 水墨、油画、赛博朋克、卡通等 |
| 色彩、构图、光影 | 6～8 | 蓝白、暖色、居中、全景等 |
| 多条件组合 | 8～10 | 主体+风格+色彩+构图 |
| 上下文依赖/指代 | 4～6 | “和上一张类似，但换成冷色” |
| 无结果/负样本 | 4～6 | 图库明确不存在的需求 |
| 同义词、中英文、轻微错字 | 4～6 | 检查鲁棒性 |

### R1.2 相关性等级

每条 Query 应使用 0～3 级人工相关性，不要只标一个正确 ID：

- `3`：高度相关，应该排在前列；
- `2`：相关，可作为参考；
- `1`：弱相关；
- `0`：不相关。

### R1.3 cases.jsonl 单条格式

```json
{
  "caseId": "style-001",
  "enabled": true,
  "group": "style",
  "query": "找一张水墨山水风格的横版参考图",
  "conversationHistory": "",
  "fixedCriteria": {
    "query": "水墨山水 横版",
    "category": "illustration",
    "tags": ["山水"],
    "styleHints": ["水墨"],
    "colorHints": [],
    "compositionHints": ["横向"],
    "favoritedOnly": false,
    "referenceMode": "style",
    "candidateSize": 20,
    "finalTopK": 5,
    "minVectorScore": 0.4
  },
  "expectedRewrite": {
    "mustContain": ["水墨", "山水"],
    "mustNotContain": [],
    "referenceMode": "style"
  },
  "relevantPictures": [
    {
      "pictureId": 12,
      "picHash": "sha256-a",
      "grade": 3
    },
    {
      "pictureId": 19,
      "picHash": "sha256-b",
      "grade": 2
    }
  ],
  "mustNotReturn": [25],
  "expectedEmpty": false,
  "notes": "主体和水墨风格同时满足"
}
```

### R1.4 标注流程

必须采用候选池标注：

1. 使用原始 Query 运行向量检索；
2. 使用人工固定的 rewritten Query 运行检索；
3. 使用 SQL 关键词检索；
4. 合并并去重所有候选；
5. 人工给候选标注 0～3；
6. 对边界案例进行第二次复核；
7. 将标注意图写入 `notes`。

不得只查看当前 Top5 后就认定其他图片不相关。

## R2. 指标计算器

### R2.1 新增测试代码

建议目录：

```text
src/test/java/com/zzp/aiagent/rag/eval/
├── RagEvalCase.java
├── RelevanceJudgment.java
├── RagEvalDatasetLoader.java
├── RagEvalMetrics.java
├── RagEvalCaseResult.java
├── RagEvalReport.java
├── RagEvalBaselineComparator.java
└── RagEvaluationIT.java
```

DTO 使用 Java `record`，不要使用 Lombok Entity。

### R2.2 必须实现的指标

#### HitRate@K

前 K 个结果中至少存在一张 `grade >= 1` 的图片，则该 Query 为 1，否则为 0。

计算 K：`1、3、5`。

#### Recall@K

```text
TopK 中召回的相关图片数 / 该 Query 全部相关图片数
```

计算 K：`3、5、20`。

#### Precision@K

```text
TopK 中相关图片数 / K
```

计算 K：`3、5`。

#### MRR@K

只看第一张相关图片的排名：

```text
RR = 1 / 第一张相关图片排名
```

没有相关结果时为 0。计算 `MRR@5`。

#### nDCG@K

使用 0～3 级相关性计算 DCG 与理想 DCG。计算 `nDCG@5`。

#### CandidateRecall@20

对 `HybridGalleryRetriever.retrieve()` 的原始候选计算 Recall@20。

#### SelectedRecall@5 / SelectedNDCG@5

对 `RagReranker.rerank()` 后的最终结果计算。

#### RerankGain@5

```text
Selected nDCG@5 - Raw Candidate nDCG@5
```

#### RelevantDropRate

候选中存在相关图片，但 selected 中没有任何相关图片的 Query 比例。

#### EmptyResultAccuracy

针对 `expectedEmpty=true` 的 Query，系统最终结果为空才算正确。

#### ForbiddenResultRate

`mustNotReturn` 中的图片进入 TopK 的比例。

### R2.3 指标测试

必须为 `RagEvalMetrics` 编写纯单元测试，至少覆盖：

- 完美排序；
- 第一张命中；
- 第三张命中；
- 完全未命中；
- 多张相关图片；
- 分级相关性；
- 空相关集；
- 空系统结果；
- 重复图片 ID；
- K 大于返回结果数量；
- no-result Query。

指标单元测试不允许连接数据库或调用模型。

## R3. Evaluation Runner

### R3.1 三种运行模式

#### fixed-retrieval

使用人工填写的 `fixedCriteria`，跳过 LLM Query Rewrite。

用途：

- 检索器回归；
- 重排序回归；
- 作为主要质量门禁。

#### rewrite-only

只运行 `RagQueryRewriteService.rewrite()`。

报告：

- JSON 解析成功率；
- fallback 使用率；
- `mustContain` 保留率；
- `mustNotContain` 违规率；
- referenceMode 准确率；
- Rewrite 前后 Recall 差值。

初期不作为强门禁，避免 LLM 随机性导致普通构建不稳定。

#### end-to-end

运行 `RagServiceImpl.buildContext()`，从 `RagContext.getTrace()` 获取：

- rewrittenQuery；
- criteria；
- candidates；
- selected；
- 最终进入上下文的 retrievedReferences。

### R3.2 默认跳过线上评测

`RagEvaluationIT` 必须默认跳过：

```java
@EnabledIfSystemProperty(
    named = "rag.eval.enabled",
    matches = "true"
)
```

普通命令：

```bash
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test
```

不得连接真实数据库或调用真实模型。

人工评测命令：

```bash
JAVA_HOME="D:/develop/java/JDK/jdk-21" \
mvn -Dtest=RagEvaluationIT \
    -Drag.eval.enabled=true \
    -Drag.eval.mode=fixed-retrieval \
    test
```

### R3.3 输出文件

每次评测输出：

```text
target/rag-eval/<timestamp>/
├── report.json
├── report.md
└── case-results.csv
```

`report.json` 用于机器比较，`report.md` 用于人工阅读，`case-results.csv` 用于定位失败 Query。

报告必须包含：

- 数据集版本；
- 当前 Git commit；
- embedding 模型；
- RAG 配置；
- 总体指标；
- 按 group 分组指标；
- 每条 Query 的原始候选和 selected ID；
- 失败案例；
- 延迟统计；
- 运行模式；
- 是否启用 MCP。

## R4. RagTrace 轻量增强

第一版不要创建 RAG Trace 数据库表。只做以下增强：

- `traceId`；
- `rewriteLatencyMs`；
- `retrieveLatencyMs`；
- `rerankLatencyMs`；
- `packLatencyMs`；
- `retrievalPath`：`VECTOR / KEYWORD_FALLBACK / EMPTY`；
- `candidatePictureIds`；
- `selectedPictureIds`；
- `candidateCount`；
- `selectedCount`。

要求：

1. 修复当前创建 `RagTrace` 时 `createTime=null` 的情况；
2. 日志优先记录 ID、分数和原因；
3. 不在 INFO 日志打印完整图片 URL、完整画像和增强 Prompt；
4. Evaluation Runner 优先直接读取 `RagContext.trace`，不要通过解析日志获取结果。

## R5. baseline 与回归门禁

### R5.1 第一次运行

第一次只生成 baseline，不判断指标是否足够高。

将人工确认后的汇总写入：

```text
src/test/resources/rag-eval/gallery-v1/baseline.json
```

### R5.2 第二次及以后

回归门禁：

- 所有标注图片解析成功率必须为 100%；
- `HitRate@5` 不允许下降超过一个测试案例；
- `Recall@5` 不允许比 baseline 下降超过 0.02；
- `nDCG@5` 不允许比 baseline 下降超过 0.02；
- `CandidateRecall@20` 不允许下降超过 0.02；
- `RelevantDropRate` 不允许升高；
- fixed-retrieval 不允许出现运行异常；
- p95 检索延迟不得超过 baseline 的 1.2 倍。

必须同时报告各 group 的指标，不能只报告总平均数。

---

# 第二部分：MCP Server/Client

## M0. 架构与版本约束

### M0.1 第一版范围

创建一个独立的 `image-retrieval-mcp-server`，只承载图片发现和只读查询能力。

MCP Server 负责：

- Pexels 关键词搜索；
- Pexels 精选图片；
- Pexels 图片详情；
- 图库向量候选召回；
- 图库关键词降级召回；
- 图库 AI 画像读取；
- 各召回维度分数计算。

主 Agent 继续负责：

- Query Rewrite；
- RAG Rerank；
- Context Packing；
- 显式参考图；
- 风格模板；
- 图片生成；
- 图片分析；
- 收藏、更新、删除；
- Pexels 图片导入图库；
- SSE 事件；
- TaskLedger；
- 对话记忆。

### M0.2 不得迁移的能力

第一版禁止迁移：

- `generateImage`；
- `analyzeImage`；
- `manageFavorite`；
- `updatePictureMetadata`；
- `deletePicture`；
- 对象存储写入；
- 数据库写操作。

### M0.3 传输方式

当前项目为 Spring AI `1.0.0`，第一版必须使用：

- MCP Server：WebMVC + SSE；
- MCP Client：SYNC + SSE。

不得使用新版 `streamable-http` 配置，除非先单独升级 Spring AI 并完成全部回归；本任务不包含 Spring AI 升级。

主应用依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

MCP Server 依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

版本由 Spring AI BOM `1.0.0` 管理，不要单独写不一致版本。

## M1. MCP Server 工程结构

新增独立子项目：

```text
mcp-servers/
└── image-retrieval-server/
    ├── pom.xml
    ├── Dockerfile
    ├── README.md
    └── src/
        ├── main/java/com/zzp/imageretrievalmcp/
        │   ├── ImageRetrievalMcpApplication.java
        │   ├── config/
        │   ├── gallery/
        │   ├── pexels/
        │   ├── tool/
        │   └── contract/
        ├── main/resources/application.yml
        └── test/java/com/zzp/imageretrievalmcp/
```

不要将现有根项目立刻改造成 Maven 多模块父工程。第一版让 MCP Server 使用独立 `pom.xml`，避免移动现有 241 个文件和破坏当前构建。

## M2. MCP 工具契约

### M2.1 第一版工具

| MCP 原始工具名 | 说明 | 是否有副作用 |
|---|---|---|
| `gallery_search` | 图库候选召回 | 否 |
| `pexels_search_photos` | Pexels 搜索 | 否 |
| `pexels_curated_photos` | Pexels 精选图片 | 否 |
| `pexels_get_photo` | Pexels 图片详情 | 否 |
| `health_check` | 能力级健康检查 | 否 |

### M2.2 通用响应要求

每个业务响应至少包含：

- `schemaVersion`，第一版固定为 `1.0`；
- `requestId`；
- `source`；
- `latencyMs`；
- 业务数据；
- 可选 `warnings`。

禁止返回：

- 图片 Base64；
- 图片二进制；
- PEXELS_API_KEY；
- ZHIPU_API_KEY；
- 数据库 URL、用户名、密码；
- 内部堆栈信息。

### M2.3 gallery_search 输入

```json
{
  "query": "水墨山水",
  "category": "illustration",
  "tags": ["山水"],
  "styleHints": ["水墨"],
  "colorHints": [],
  "compositionHints": ["横向"],
  "favoritedOnly": false,
  "referenceMode": "style",
  "candidateSize": 20,
  "minVectorScore": 0.4
}
```

校验要求：

- query 去除首尾空格后不能为空；
- query 长度不得超过 500；
- candidateSize 范围为 1～50；
- minVectorScore 范围为 0～1；
- 单个 hints 数组最大 10 项；
- 单项字符串最大 100 字符。

### M2.4 gallery_search 输出

```json
{
  "schemaVersion": "1.0",
  "requestId": "uuid",
  "source": "gallery",
  "query": "水墨山水",
  "retrievalPath": "VECTOR",
  "latencyMs": 82,
  "candidates": [
    {
      "pictureId": 12,
      "picHash": "sha256...",
      "name": "水墨山水",
      "introduction": "...",
      "category": "illustration",
      "tags": ["水墨", "山水"],
      "favorited": true,
      "picWidth": 1920,
      "picHeight": 1080,
      "picFormat": "jpg",
      "profile": {
        "subject": "山水",
        "scene": "自然",
        "style": "水墨",
        "colors": "黑白灰",
        "composition": "横向",
        "lighting": "柔和",
        "mood": "宁静",
        "imagePrompt": "..."
      },
      "vectorScore": 0.81,
      "keywordScore": 10.0,
      "metadataScore": 25.0
    }
  ]
}
```

MCP Server 返回原始 candidates，不在 Server 内执行主 Agent 的 `RagRerankerImpl`。保持主应用当前的 RAG 策略所有权。

### M2.5 Pexels 输出

只返回：

- Pexels photo ID；
- width、height；
- alt；
- photographer；
- photographerUrl；
- Pexels 页面 URL；
- avgColor；
- original、large2x、large、medium、small、portrait、landscape、tiny URL。

## M3. MCP Server 配置

建议：

```yaml
server:
  port: 8232

spring:
  application:
    name: image-retrieval-mcp-server
  ai:
    mcp:
      server:
        enabled: true
        name: image-retrieval-server
        version: 1.0.0
        type: SYNC
        sse-endpoint: /sse
        sse-message-endpoint: /mcp/message
        capabilities:
          tool: true
          resource: false
          prompt: false
          completion: false
```

Server 环境变量：

```text
PEXELS_API_KEY
ZHIPU_API_KEY
DB_URL
DB_USERNAME
DB_PASSWORD
```

图库 MCP Server 使用只读数据库账号。第一版只允许读取：

- `gallery_picture`；
- `picture_ai_profile`；
- `picture_vector_store`。

Pexels `base-url` 必须配置化，默认值可以是官方 API 地址，测试时必须能够替换为本地假 HTTP Server。

## M4. 主应用 MCP Client 配置

主应用增加：

```yaml
app:
  integrations:
    image-retrieval:
      mode: ${IMAGE_RETRIEVAL_MODE:local}

spring:
  ai:
    mcp:
      client:
        enabled: true
        name: ai-agent-image-retrieval-client
        version: 1.0.0
        initialized: true
        request-timeout: 20s
        type: SYNC
        toolcallback:
          enabled: false
        sse:
          connections:
            image-retrieval:
              url: ${IMAGE_RETRIEVAL_MCP_URL:http://localhost:8232}
              sse-endpoint: /sse
```

模式：

- `local`：现有本地实现；
- `mcp`：调用独立 MCP Server。

默认仍为 `local`，直到 MCP 等价性验收通过。

### 为什么第一版禁用自动 ToolCallback

第一版不直接把 MCP ToolCallback 注册进 `ChatClient`，原因：

1. 当前 Spring AI 1.0.0 会为 MCP 工具名添加客户端名前缀；
2. 当前 TaskPlanner、ToolCapabilityRegistry、BackendToolExecutor 使用固定工具名；
3. 当前 Pexels 和图库工具依赖主应用 `ToolContext` 发送 SSE 进度和图片候选；
4. Spring AI 1.0.0 的 MCP ToolCallback 不会透传当前主应用 ToolContext；
5. 直接替换会同时破坏工具名、Manual 路径和前端候选卡片。

第一版使用稳定外观：

```text
LLM / Planner
  → 原有 @Tool 方法和工具名
  → ImageRetrievalGateway
  → McpSyncClient.callTool()
  → MCP Server
```

这仍然属于主 Agent 作为 MCP Client 消费 MCP 工具，只是不让模型直接感知远程前缀工具名。

## M5. 主应用客户端适配层

新增：

```text
src/main/java/com/zzp/aiagent/integration/mcp/
├── ImageRetrievalGateway.java
├── LocalImageRetrievalGateway.java
├── McpImageRetrievalGateway.java
├── McpToolInvoker.java
├── McpIntegrationProperties.java
├── McpToolNames.java
└── dto/
    ├── GallerySearchMcpRequest.java
    ├── GallerySearchMcpResponse.java
    ├── GalleryCandidateMcpDTO.java
    ├── GalleryProfileMcpDTO.java
    ├── PexelsSearchMcpRequest.java
    ├── PexelsSearchMcpResponse.java
    └── PexelsPhotoMcpDTO.java
```

### M5.1 ImageRetrievalGateway 方法

至少包含：

```text
searchGallery(RagSearchCriteria)
searchPexels(PexelsSearchRequest)
curatedPexels(perPage, page)
getPexelsPhoto(photoId)
```

接口不得暴露 MCP SDK 类型。

### M5.2 McpToolInvoker 职责

只负责：

1. 注入 Spring 配置产生的 `List<McpSyncClient>`；
2. 验证只有目标 image-retrieval 客户端；
3. 构造 `McpSchema.CallToolRequest`；
4. 调用 `McpSyncClient.callTool()`；
5. 判断 `CallToolResult.isError()`；
6. 提取 `TextContent.text()`；
7. 使用 Jackson 转换成响应 DTO；
8. 校验 `schemaVersion`；
9. 把协议错误、超时、JSON 错误转换为统一 `BusinessException`；
10. 记录 toolName、requestId、latency。

业务类不得直接处理 `CallToolResult`。

### M5.3 Bean 选择

使用 `@ConditionalOnProperty` 确保同一时间只有一种 Gateway：

```text
mode=local → LocalImageRetrievalGateway
mode=mcp   → McpImageRetrievalGateway
```

不得让两个实现同时注册并通过 `@Primary` 随机解决冲突。

## M6. Pexels 迁移

修改：

- `PexelsSearchTools`；
- `BackendToolExecutor`；
- Pexels 相关测试。

迁移规则：

```text
pexelsSearchPhotos  → ImageRetrievalGateway.searchPexels()
pexelsCuratedPhotos → ImageRetrievalGateway.curatedPexels()
pexelsGetPhoto      → ImageRetrievalGateway.getPexelsPhoto()
```

`pexelsSearchAndImport` 保留在主应用：

1. 通过 MCP 搜索候选；
2. 选择候选图片 URL；
3. 主应用调用现有 `GalleryService.importUrl()`；
4. 主应用写数据库和对象存储；
5. MCP Server 不获得图库写权限。

必须保留：

- 原有 `@Tool` 名称；
- 原有中文摘要；
- `image_candidates` SSE 事件；
- `ToolProgressContext`；
- TaskLedger 成功/失败记录；
- Pexels 摄影师和来源信息；
- 当前最大搜索和导入数量限制。

## M7. 图库候选召回迁移

继续使用现有 `HybridGalleryRetriever` 作为主应用 Port：

```text
HybridGalleryRetriever
├── LocalHybridGalleryRetrieverImpl
└── McpHybridGalleryRetrieverImpl
```

`McpHybridGalleryRetrieverImpl`：

1. 将 `RagSearchCriteria` 转换为 `GallerySearchMcpRequest`；
2. 调用 `gallery_search`；
3. 将 MCP DTO 转换为当前 `RagCandidate`；
4. 完整保留：
   - pictureId；
   - picHash；
   - name；
   - introduction；
   - tags；
   - profile 字段；
   - vectorScore；
   - keywordScore；
   - metadataScore；
5. 不在该实现中重复执行 rerank；
6. 返回给现有 `RagRerankerImpl`。

切换后应尽量不修改：

- `RagServiceImpl` 的整体编排顺序；
- `RagRerankerImpl`；
- `RagContextPackerImpl`；
- `PromptReferenceAssembler`；
- `GalleryAgentTools` 对外工具名。

## M8. 错误处理与故障治理

新增 Resilience4j 配置：

```yaml
resilience4j:
  circuitbreaker:
    instances:
      image-retrieval-mcp:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      image-retrieval-mcp:
        max-attempts: 2
        wait-duration: 300ms
```

规则：

| 情况 | 行为 |
|---|---|
| 合法查询但没有结果 | 成功响应，返回空数组 |
| 输入不合法 | MCP Tool Error |
| Pexels 429 | 限流业务错误 |
| Pexels 401/403 | 配置/认证错误，不泄露 Key |
| MCP 超时 | 主 Agent 返回“图片检索服务暂不可用” |
| MCP 无法连接 | 当前工具失败，但主应用进程不能崩溃 |
| schemaVersion 不支持 | 拒绝解析并记录版本 |
| MCP JSON 解析失败 | 业务错误，记录 requestId |

只读搜索可以重试。图库写入、导入等有副作用操作不得由 MCP 层自动重试。

第一版不实现“单次 MCP 调用失败后自动调用本地实现”，避免隐藏部署故障。需要回滚时由配置从 `mcp` 切换为 `local`。

---

# 第三部分：测试与验收

## T1. MCP Server 单元测试

至少覆盖：

- `gallery_search` 空 Query；
- Query 超长；
- candidateSize 小于 1、大于 50；
- minVectorScore 非法；
- 向量正常召回；
- 向量为空时关键词降级；
- 向量异常时关键词降级；
- 图片记录缺失；
- 图片画像缺失；
- Pexels Query URL 编码；
- Pexels 空结果；
- Pexels 429；
- Pexels 401；
- Pexels 500；
- Pexels JSON 字段缺失；
- 响应 schemaVersion；
- 响应不包含密钥、数据库信息和 Base64。

Pexels 测试使用本地假 HTTP Server，禁止调用真实 Pexels。

## T2. MCP 协议集成测试

启动 MCP Server 后，由真实 `McpSyncClient`：

1. 连接 `/sse`；
2. 执行 `listTools`；
3. 验证五个工具存在；
4. 调用 `health_check`；
5. 调用 `gallery_search`；
6. 检查返回是否为合法 JSON；
7. 检查 `schemaVersion=1.0`；
8. 调用不存在工具并验证协议错误；
9. 检查关闭 ApplicationContext 后客户端资源被释放。

## T3. 主应用测试

新增或扩展：

```text
McpToolInvokerTest
McpImageRetrievalGatewayTest
McpHybridGalleryRetrieverTest
McpPexelsToolsTest
McpProfileContextTest
BackendToolExecutorTest
```

必须验证：

- MCP Tool Error 转换为 `BusinessException`；
- MCP 返回空 content 时错误可控；
- 非 TextContent 响应错误可控；
- 非法 JSON 错误可控；
- schemaVersion 不匹配时拒绝；
- `GalleryCandidateMcpDTO → RagCandidate` 字段完整；
- Pexels 搜索后仍发送 `image_candidates`；
- TaskLedger 仍能记录成功和失败；
- Manual 和 Auto 最终使用同一个 Gateway；
- `mode=local` 的 Spring Context 能启动；
- `mode=mcp` 的 Spring Context 能启动；
- 普通 test profile 不要求 MCP Server 存在。

## T4. RAG local/MCP 等价性测试

使用相同：

- PostgreSQL 数据；
- pgvector 表；
- embedding 模型；
- Query；
- RagSearchCriteria；
- topK；
- minScore；
- rerank 权重。

分别运行：

```text
IMAGE_RETRIEVAL_MODE=local
IMAGE_RETRIEVAL_MODE=mcp
```

验收：

- 原始 candidate ID 集合一致；
- candidate 顺序一致；
- vectorScore 误差小于 `1e-6`；
- keywordScore 误差小于 `1e-6`；
- metadataScore 误差小于 `1e-6`；
- selected ID 和顺序一致；
- HitRate、Recall、MRR、nDCG 一致；
- fixed-retrieval 每条 Query 的成功/失败状态一致；
- MCP 本地网络封装额外 p95 延迟小于 200ms，不包括 embedding 调用耗时。

Pexels 线上结果可能变化，Pexels 等价性测试必须使用相同的固定 HTTP 响应。

## T5. 构建命令

主应用：

```bash
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test
```

MCP Server：

```bash
JAVA_HOME="D:/develop/java/JDK/jdk-21" \
mvn -f mcp-servers/image-retrieval-server/pom.xml test
```

RAG 手工评测：

```bash
JAVA_HOME="D:/develop/java/JDK/jdk-21" \
mvn -Dtest=RagEvaluationIT \
    -Drag.eval.enabled=true \
    -Drag.eval.mode=fixed-retrieval \
    test
```

---

# 第四部分：推荐 Commit 划分

## Commit 1：RAG 数据集格式与指标计算器

范围：

- 新增 eval DTO；
- 新增 dataset loader；
- 新增指标计算器；
- 新增纯单元测试。

禁止：修改生产检索逻辑。

验收：普通 `mvn test` 通过。

## Commit 2：RAG Evaluation Runner

范围：

- fixed-retrieval；
- rewrite-only；
- end-to-end；
- JSON/Markdown/CSV 报告。

禁止：调整 RAG 权重和 Prompt。

## Commit 3：人工评测集与 baseline

范围：

- corpus manifest；
- 40～60 条 cases；
- baseline；
- annotation guide。

禁止：为了提高 baseline 而修改算法。

## Commit 4：RagTrace 轻量增强

范围：阶段耗时、candidate/selected ID、retrievalPath、createTime。

禁止：创建数据库表。

## Commit 5：MCP 契约与 Server 骨架

范围：

- 独立 pom；
- Spring Boot Application；
- MCP 配置；
- DTO；
- `health_check`；
- 契约测试。

禁止：接入主 Agent。

## Commit 6：Pexels MCP 工具

范围：search、curated、getPhoto、假 HTTP 测试。

禁止：迁移图库和主 Agent。

## Commit 7：图库 MCP 召回

范围：vector、metadata、keyword fallback、gallery_search。

禁止：在 Server 中执行主 Agent rerank。

## Commit 8：主应用 MCP Client Gateway

范围：McpToolInvoker、Gateway、配置开关、错误转换。

默认：`mode=local`。

## Commit 9：Pexels 切换 MCP

范围：Pexels tools 和 manual executor。

必须保留：工具名、SSE、TaskLedger。

## Commit 10：图库召回切换 MCP

范围：McpHybridGalleryRetrieverImpl、Bean 条件选择。

必须运行 local/MCP 等价性评测。

## Commit 11：Docker Compose、健康检查、熔断与文档

范围：部署、环境变量、healthcheck、启动顺序、README。

## 后续独立 Commit：算法优化

只有上述全部验收通过后，才允许研究：

- 真正的 vector/keyword 候选并集；
- RRF；
- BM25/全文检索；
- rerank 权重；
- Query Rewrite Prompt；
- minScore；
- 负样本拒答；
- LLM/VLM reranker。

任何优化必须给出 baseline 与修改后评测对比。

---

# 第五部分：阶段完成报告模板

本地 AI 每完成一个阶段，必须按照以下格式回复：

````markdown
## 阶段

例如：Commit 1 / RAG 指标计算器

## 变更摘要

- ...

## 修改文件

- 修改：...
- 新增：...
- 删除：无

## 验证命令

```bash
...
```

## 验证结果

- 测试总数：
- 通过：
- 失败：
- 跳过：

## 与任务书的偏离

- 无；或说明具体偏离、原因和影响。

## 遗留问题

- ...

## 是否满足进入下一阶段的条件

- 是/否
````

---

# 第六部分：立即停止条件

遇到以下任一情况必须停止，不得继续扩大改动：

1. 现有测试在本阶段修改前就失败；
2. Maven 依赖与 Spring AI 1.0.0 API 不兼容；
3. local 与 MCP 返回的 candidate ID 不一致；
4. MCP 改造导致原有 SSE `image_candidates` 消失；
5. Manual executor 不再支持原计划中的工具；
6. `mode=local` 无法启动；
7. 普通 `mvn test` 开始依赖真实 API Key 或 MCP Server；
8. 工具名称发生变化；
9. MCP 响应包含 Base64、密钥或数据库信息；
10. 必须修改 REST API 或数据库结构才能继续，但本文没有授权；
11. 需要升级 Spring AI 才能继续；
12. 无法解释 RAG 指标变化来源。

停止后只报告：问题、证据、已修改文件、建议方案，不得擅自选择高风险方案。

---

# 参考资料

- Spring AI 1.0.0 MCP Client：<https://github.com/spring-projects/spring-ai/blob/v1.0.0/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-client-boot-starter-docs.adoc>
- Spring AI 1.0.0 MCP Server：<https://github.com/spring-projects/spring-ai/blob/v1.0.0/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-server-boot-starter-docs.adoc>
- Spring AI 1.0.0 SyncMcpToolCallback：<https://github.com/spring-projects/spring-ai/blob/v1.0.0/mcp/common/src/main/java/org/springframework/ai/mcp/SyncMcpToolCallback.java>
- MCP Tools Specification：<https://modelcontextprotocol.io/specification/draft/server/tools>
- RAGAS：<https://aclanthology.org/2024.eacl-demo.16/>
- RAGChecker：<https://papers.nips.cc/paper_files/paper/2024/hash/27245589131d17368cccdfa990cbf16e-Abstract-Datasets_and_Benchmarks_Track.html>
- BEIR：<https://openreview.net/forum?id=wCu6T5xFjeJ>
