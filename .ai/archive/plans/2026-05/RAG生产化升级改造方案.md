# RAG 生产化升级改造方案

## 目标

当前 `ai-agent` 已具备三层 RAG、图库管理、AI 画像、风格模板和前端调试台。下一阶段目标是把当前 demo/本地化实现升级为更接近生产环境的架构：

- Spring AI 从 milestone 版本升级到稳定版，并引入 BOM 统一依赖管理。
- 本地 JSON 存储升级为 PostgreSQL 数据库。
- 当前本地图片存储改造成可插拔对象存储接口，开发期继续本地存储，后续上线切腾讯云 COS。
- 向量存储从 `SimpleVectorStore` 升级到 PostgreSQL + pgvector。
- RAG 检索从单纯向量召回升级为 LLM Query Rewrite + Hybrid Search + Metadata Filter + Rule Rerank + Debug Trace。
- 清理旧版 `knowledge` 和 `kb-data`，保留必要 migration script。

本方案按阶段拆分，并支持多个 agent 并行开发。

## 技术栈选型

### Spring AI 稳定版 + BOM

选型：

```text
Spring AI 1.1.x 稳定版
Spring AI BOM
```

原因：

- 当前 `1.0.0-M6` 是 milestone 版本，Advisor、VectorStore、ChatMemory 等 API 后续存在变化风险。
- RAG 后续要接 PgVectorStore，使用 BOM 能避免 Spring AI 子模块版本不一致。
- 稳定版对 ChatClient、Advisor、VectorStore、EmbeddingModel 的行为更清晰，后续维护成本低。

影响范围：

```text
pom.xml
advisor/*
PictureApp
memory/*
knowledge/config/*
rag/*
tests
```

### PostgreSQL + pgvector

选型：

```text
PostgreSQL 存业务数据
pgvector 存图片 AI 画像向量
Spring AI PgVectorStore 或自定义 SQL Repository
```

原因：

- 当前阶段用户已确定直接引入 PostgreSQL。
- 业务数据和向量数据在同一个数据库中，数据一致性更简单。
- 可以用 SQL 同时做 metadata filter 和向量排序。
- 比 MySQL + Milvus 少维护一个独立向量系统。
- 比 `SimpleVectorStore` 更可靠，支持持久化、查询、备份和后续扩展。

适用规模：

```text
开发期、测试期、中小规模图库、几十万级以内向量检索
```

后续如果数据到百万级以上，再评估 Milvus。

### Flyway

选型：

```text
Flyway
```

原因：

- 管理 PostgreSQL 表结构和 pgvector 扩展初始化。
- 方便后续并入 Picture-Backend 或做环境迁移。
- 比手写 SQL 初始化更可控。

### 本地存储 + 腾讯云 COS 双实现

选型：

```text
ObjectStorageService 抽象
LocalObjectStorageService 作为 dev/local 默认实现
CosObjectStorageService 作为 prod 实现
```

原因：

- 当前测试不依赖云服务，开发体验稳定。
- 后续上线只切换 profile 和配置，不改 GalleryService 主逻辑。
- 图片原文件、生成图、缩略图都统一走对象存储接口。

### Redis

选型：

```text
Redis 继续存 ChatMemory 和临时状态
```

原因：

- 当前项目已有 RedisChatMemory。
- 会话记忆属于短期上下文，不适合放 PostgreSQL。
- 后续可用于异步任务状态、RAG Trace 缓存、限流等。

### RAG 增强技术

选型：

```text
LLM Query Rewrite
pgvector semantic search
PostgreSQL metadata filter
简单 keyword score
Java Rule Rerank
Prompt Compression / Context Packing
RAG Debug & Trace
RAG Evaluation
```

原因：

- LLM 负责理解模糊意图。
- pgvector 负责语义召回。
- SQL 负责强过滤和业务条件。
- 规则重排可解释、便宜、易调参。
- Trace 和 Evaluation 能让 RAG 效果持续可测，而不是靠感觉。

暂不引入：

```text
Elasticsearch
Milvus
专用 reranker 模型
图像 embedding 模型
```

原因：

- 当前阶段 PostgreSQL + pgvector 足够。
- 新增过多中间件会增加部署、调试和数据一致性成本。

## 总体改造阶段

```text
第一阶段：依赖升级
第二阶段：存储抽象与 PostgreSQL 落库
第三阶段：对象存储抽象与 COS 预留
第四阶段：向量库升级为 pgvector
第五阶段：RAG 检索增强
第六阶段：清理旧实现与迁移脚本
```

建议合并顺序：

```text
依赖升级 -> PostgreSQL 表结构 -> Repository 落库 -> ObjectStorageService -> VectorIndexService -> RAG 增强 -> 清理旧模块
```

## 第一阶段：依赖升级

### 目标

将 Spring AI 从 `1.0.0-M6` 升级到稳定版，并引入 BOM。

### 任务拆分

1. 修改 `pom.xml`
   - 新增 `spring-ai.version`。
   - 引入 `spring-ai-bom`。
   - 移除 Spring AI 子依赖上的显式版本。

2. 检查依赖 artifact 名称
   - OpenAI starter。
   - PgVectorStore 依赖。
   - test 依赖。

3. 修复 API 变化
   - `ChatClient`。
   - `MessageChatMemoryAdvisor`。
   - `CallAroundAdvisor` / `StreamAroundAdvisor`。
   - `AdvisedRequest` / `AdvisedResponse`。
   - `VectorStore`。
   - `SearchRequest`。

4. 跑基础测试
   - Advisor 单测。
   - Chat 非流式。
   - Chat stream。
   - Memory。
   - RAG 相关 mock 测试。

### 验收标准

```text
mvn test 通过
ChatController 正常
PictureApp 生图模式正常
Advisor order 和异常兜底行为不变
```

### 风险

- Spring AI Advisor API 可能有签名变化。
- VectorStore 删除和 SearchRequest 构造可能有变化。
- 旧 M6 的已知流式异常兜底问题可能在新版中表现不同，需要回归测试。

## 第二阶段：存储抽象与 PostgreSQL 落库

### 目标

将当前本地 JSON 存储：

```text
gallery-data/pictures.json
gallery-data/ai-profiles.json
```

升级为 PostgreSQL 表。

### 数据表设计

### gallery_picture

```sql
create table gallery_picture (
    id bigserial primary key,
    url varchar(1024),
    storage_key varchar(512),
    thumbnail_url varchar(1024),
    thumbnail_storage_key varchar(512),
    name varchar(255) not null,
    introduction text,
    category varchar(64),
    tags jsonb,
    pic_size bigint,
    pic_width integer,
    pic_height integer,
    pic_scale double precision,
    pic_format varchar(32),
    user_id bigint not null default 1,
    space_id bigint not null default 0,
    review_status integer not null default 1,
    pic_color varchar(64),
    source_type varchar(64),
    favorited boolean not null default false,
    create_time timestamp not null default now(),
    update_time timestamp not null default now(),
    is_delete integer not null default 0
);
```

### picture_ai_profile

```sql
create table picture_ai_profile (
    id bigserial primary key,
    picture_id bigint not null,
    subject text,
    scene text,
    style text,
    colors text,
    composition text,
    lighting text,
    mood text,
    image_prompt text,
    index_text text,
    vector_status integer not null default 0,
    analyzed_at timestamp,
    create_time timestamp not null default now(),
    update_time timestamp not null default now(),
    unique (picture_id)
);
```

### style_template

可选。第一版仍可保留 yml，后续落库：

```sql
create table style_template (
    id bigserial primary key,
    code varchar(128) not null unique,
    name varchar(255) not null,
    scene varchar(64),
    category varchar(64),
    keywords jsonb,
    prompt text,
    negative_prompt text,
    suggested_dimensions varchar(64),
    enabled boolean not null default true,
    create_time timestamp not null default now(),
    update_time timestamp not null default now()
);
```

### Repository 设计

保持当前接口不变：

```java
public interface GalleryPictureRepository {
    GalleryPicture save(GalleryPicture picture);
    Optional<GalleryPicture> findById(Long id);
    List<GalleryPicture> findByIds(List<Long> ids);
    List<GalleryPicture> findAll();
    void deleteById(Long id);
}
```

新增实现：

```text
JsonFileGalleryPictureRepository      @Profile("local-json")
PostgresGalleryPictureRepository      @Profile("postgres")
```

AI 画像同理：

```text
JsonFilePictureAiProfileRepository    @Profile("local-json")
PostgresPictureAiProfileRepository    @Profile("postgres")
```

### 推荐持久层技术

当前项目可以选：

```text
Spring JDBC / NamedParameterJdbcTemplate
```

原因：

- 表不多，SQL 明确。
- 对 PostgreSQL jsonb、pgvector 后续扩展更直接。
- 不必在 ai-agent 内引入完整 MyBatis-Plus 体系。

如果强对齐 Picture-Backend，也可以用 MyBatis-Plus，但会增加迁移量。

### 验收标准

```text
图库上传数据写入 PostgreSQL
分页查询来自 PostgreSQL
收藏状态更新写入 PostgreSQL
AI 画像写入 PostgreSQL
JSON 实现仍可在 local-json profile 下使用
```

## 第三阶段：对象存储抽象与 COS 预留

### 目标

将图片文件读写从 `gallery-data/images` 中剥离出来，统一通过对象存储接口。

### 接口设计

```java
public interface ObjectStorageService {
    StoredObject upload(byte[] bytes, String key, String contentType);
    byte[] download(String key);
    void delete(String key);
    String getUrl(String key);
}
```

```java
public record StoredObject(
        String key,
        String url,
        String contentType,
        Long size
) {}
```

### 本地实现

```text
LocalObjectStorageService
profile: local / dev
root: gallery-data/images
```

key 设计：

```text
gallery/{userId}/{pictureId}/origin.{ext}
gallery/{userId}/{pictureId}/thumbnail.{ext}
generated/{userId}/{chatId}/{timestamp}.{ext}
```

本地映射：

```text
gallery-data/images/gallery/{userId}/{pictureId}/origin.{ext}
```

### COS 实现

```text
CosObjectStorageService
profile: prod
```

配置：

```yaml
app:
  storage:
    type: local
    local:
      root: gallery-data/images
    cos:
      secret-id: ${COS_SECRET_ID:}
      secret-key: ${COS_SECRET_KEY:}
      region: ${COS_REGION:}
      bucket: ${COS_BUCKET:}
      base-url: ${COS_BASE_URL:}
```

### GalleryService 改造

当前：

```text
GalleryServiceImpl 直接 Files.write / Files.readAllBytes
```

改造后：

```text
GalleryServiceImpl 只依赖 ObjectStorageService
```

### 验收标准

```text
local profile 下图片仍保存在本地
prod profile 下可切换 COS
GalleryPicture 保存 storageKey 和 url
serveFile 不直接拼本地路径
```

## 第四阶段：向量库升级为 pgvector

### 目标

将当前 `SimpleVectorStore` 替换为 PostgreSQL + pgvector。

### 数据库初始化

Flyway 脚本：

```sql
create extension if not exists vector;
```

推荐单独建向量表：

```sql
create table picture_vector_index (
    picture_id bigint primary key,
    embedding vector(1024),
    index_text text,
    metadata jsonb,
    create_time timestamp not null default now(),
    update_time timestamp not null default now()
);
```

维度需要按实际 embedding 模型确认。智谱 `embedding-2` 的维度需以实际返回为准，不能硬猜。

### VectorIndexService 抽象

新增：

```java
public interface VectorIndexService {
    void upsertPictureVector(Long pictureId, String indexText, Map<String, Object> metadata);
    void deletePictureVector(Long pictureId);
    List<VectorSearchHit> search(RagSearchCriteria criteria);
    void rebuildAll();
}
```

```java
public record VectorSearchHit(
        Long pictureId,
        Double vectorScore,
        Map<String, Object> metadata
) {}
```

实现：

```text
SimpleVectorIndexService      @Profile("local-json")
PgVectorIndexService          @Profile("postgres")
```

### 检索 SQL 思路

```sql
select
    g.id as picture_id,
    1 - (v.embedding <=> :query_embedding) as vector_score
from picture_vector_index v
join gallery_picture g on g.id = v.picture_id
join picture_ai_profile p on p.picture_id = g.id
where g.is_delete = 0
  and p.vector_status = 1
  and (:favorited_only = false or g.favorited = true)
order by v.embedding <=> :query_embedding
limit :candidate_size;
```

### 重建索引接口

建议提供管理接口：

```text
POST /admin/vector/rebuild
POST /admin/vector/reindex/{pictureId}
```

开发期可不加鉴权，但上线前必须限制权限。

### 验收标准

```text
图片分析完成后写入 pgvector
删除图片时删除向量
重建索引可用
RAG 检索从 pgvector 返回候选图
SimpleVectorStore 不再作为主实现
```

## 第五阶段：RAG 检索增强

### 目标

当前 RAG 是：

```text
用户原话 -> 向量检索 -> topK -> 拼 Prompt
```

升级为：

```text
用户需求
  -> LLM Query Rewrite
  -> Hybrid Search
  -> Metadata Filter
  -> Rule Rerank
  -> Context Packing
  -> PromptReferenceAssembler
  -> RAG Debug Trace
```

### 5.1 LLM Query Rewrite

新增：

```java
public interface RagQueryRewriteService {
    RagRewriteResult rewrite(ChatRequest request);
}
```

```java
public record RagRewriteResult(
        String searchQuery,
        String category,
        List<String> tags,
        List<String> styleHints,
        List<String> colorHints,
        List<String> compositionHints,
        String referenceMode,
        String templateHint
) {}
```

LLM 输出示例：

```json
{
  "searchQuery": "AI 产品介绍 PPT 配图 商务 科技感 高级 蓝色 留白 扁平插画",
  "category": "ppt",
  "tags": ["AI", "PPT", "商务", "科技", "留白"],
  "styleHints": ["高级", "扁平插画", "科技感"],
  "colorHints": ["蓝色", "低饱和"],
  "compositionHints": ["留白", "中心主体", "适合标题叠加"],
  "referenceMode": "style",
  "templateHint": "ppt-tech-isometric"
}
```

失败降级：

```text
searchQuery = request.message()
category = null
tags = []
```

### 5.2 Hybrid Search

新增：

```java
public record RagSearchCriteria(
        String query,
        String category,
        List<String> tags,
        List<String> styleHints,
        List<String> colorHints,
        List<String> compositionHints,
        Boolean favoritedOnly,
        String referenceMode,
        int candidateSize,
        int finalTopK,
        double minVectorScore
) {}
```

新增：

```java
public interface HybridGalleryRetriever {
    List<RagCandidate> retrieve(RagSearchCriteria criteria);
}
```

候选对象：

```java
public record RagCandidate(
        GalleryPicture picture,
        PictureAiProfile profile,
        double vectorScore,
        double keywordScore,
        double metadataScore,
        double finalScore,
        List<String> reasons
) {}
```

实现策略：

```text
pgvector 召回 candidateSize=20~30
SQL hard filter 过滤无效数据
Java 计算 keywordScore
返回候选列表给 reranker
```

### 5.3 Metadata Filter

Hard filter：

```text
is_delete = 0
review_status = 1
vector_status = 1
user_id = 当前用户 or 公共图库
favorited = true（当 favoritedOnly=true）
```

Soft filter：

```text
category 命中
tags 命中
sourceType 命中
picScale 适配场景
styleHints 命中
colorHints 命中
compositionHints 命中
```

PPT 场景：

```text
picScale between 1.5 and 2.0
category in ('ppt', 'business', 'illustration')
tags 包含 PPT / 汇报 / 商务 / 科技 / 留白
```

### 5.4 Rule Rerank

新增：

```java
public interface RagReranker {
    List<RagCandidate> rerank(List<RagCandidate> candidates, RagSearchCriteria criteria);
}
```

评分建议：

```text
finalScore =
  vectorScore * 50
+ keywordScore * 15
+ categoryScore
+ tagScore
+ favoriteScore
+ sourceScore
+ qualityScore
+ aspectRatioScore
```

规则示例：

```text
category 命中 +12
每个 tag 命中 +4，最多 +16
收藏图 +10
sourceType=reference +8
sourceType=generated_saved +6
画像完整 +5
PPT 场景且 16:9 +8
命中色彩偏好 +4
命中构图偏好 +4
```

### 5.5 Prompt Compression / Context Packing

新增：

```java
public interface RagContextPacker {
    PackedRagContext pack(RagContext context, RagSearchCriteria criteria);
}
```

```java
public record PackedRagContext(
        String explicitReferencesText,
        String retrievedReferencesText,
        String styleTemplateText,
        int totalChars
) {}
```

按 `referenceMode` 选择字段：

```text
style:
  style, colors, lighting, mood

color:
  colors, mood, style

composition:
  composition, scene, subject

overall:
  subject, scene, style, colors, composition, lighting, mood
```

限制：

```text
显式参考图最多 3 张
RAG 召回图最多 5 张
每张图最多 300~500 字
总上下文最多 2500 字
```

### 5.6 RAG Debug & Trace

新增表：

```sql
create table rag_trace_log (
    id bigserial primary key,
    chat_id varchar(128),
    user_id bigint,
    original_query text,
    rewritten_query text,
    criteria_json jsonb,
    candidates_json jsonb,
    selected_json jsonb,
    template_code varchar(128),
    enhanced_prompt text,
    latency_ms bigint,
    create_time timestamp not null default now()
);
```

新增服务：

```java
public interface RagTraceService {
    void record(RagTrace trace);
}
```

前端 debug 数据：

```json
{
  "rewrite": {},
  "candidates": [],
  "selected": [],
  "template": {},
  "enhancedPrompt": "..."
}
```

### 5.7 RAG Evaluation

测试资源：

```text
src/test/resources/rag/eval-cases.json
```

示例：

```json
{
  "query": "生成一张适合 AI 产品介绍页的 PPT 配图",
  "expectedCategory": "ppt",
  "expectedTags": ["AI", "PPT", "科技"],
  "forbiddenTags": ["儿童", "国潮"],
  "expectedTemplate": "ppt-tech-isometric"
}
```

指标：

```text
Recall@5
Precision@5
Template Accuracy
Forbidden Hit Rate
Prompt Coverage
```

技术：

```text
JUnit
Testcontainers PostgreSQL
固定测试图库 fixture
```

## 第六阶段：清理旧实现与迁移脚本

### 目标

清理历史遗留的旧知识库实现，避免两套 RAG 入口并存。

### 清理内容

确认新链路稳定后移除或标记 legacy：

```text
knowledge/
KnowledgeController
kb-data/
SimpleVectorStore 主实现
```

如果暂不删除，至少：

```text
@Deprecated
@Profile("legacy-knowledge")
```

### Migration Script

保留：

```text
scripts/migrate-gallery-json-to-postgres
scripts/rebuild-vector-index
scripts/import-style-templates
```

迁移流程：

```text
pictures.json -> gallery_picture
ai-profiles.json -> picture_ai_profile
style-templates.yml -> style_template 或继续保留 yml
picture_ai_profile.index_text -> pgvector embedding
```

### 验收标准

```text
新图库/RAG 链路可完整运行
旧 /knowledge 不再被前端使用
kb-data 不再提交版本库
gallery-data 不提交版本库
迁移脚本可重复执行或有幂等保护
```

## 并行 Agent 分工

## Agent A：Spring AI 升级

职责：

- 修改 `pom.xml`。
- 引入 Spring AI BOM。
- 修复 ChatClient / Advisor / VectorStore API。
- 保证基础测试通过。

禁止修改：

- PostgreSQL 表结构。
- RAG scoring 规则。
- 前端页面。

交付：

```text
mvn test 通过
Advisor 行为不变
```

## Agent B：PostgreSQL 与 Repository

职责：

- 引入 PostgreSQL 依赖。
- 引入 Flyway。
- 创建 migration SQL。
- 实现 `PostgresGalleryPictureRepository`。
- 实现 `PostgresPictureAiProfileRepository`。
- 保留 JsonFile profile。

禁止修改：

- RAG Query Rewrite。
- PromptReferenceAssembler。
- ObjectStorageService。

交付：

```text
gallery_picture 表可读写
picture_ai_profile 表可读写
local-json profile 可回退
```

## Agent C：对象存储

职责：

- 新增 `ObjectStorageService`。
- 实现 `LocalObjectStorageService`。
- 预留 `CosObjectStorageService`。
- 改造 `GalleryServiceImpl`。

禁止修改：

- PostgreSQL schema。
- RAG 检索逻辑。
- Spring AI 依赖。

交付：

```text
本地图片上传、读取、删除正常
GalleryPicture 保存 storageKey
COS 配置位齐全但可不启用
```

## Agent D：pgvector 与 VectorIndexService

职责：

- 初始化 pgvector。
- 实现 `VectorIndexService`。
- 将 `PictureAiProfileService` 的向量写入改为调用 `VectorIndexService`。
- 提供重建索引方法。

依赖：

- Agent B 的 PostgreSQL 表。

禁止修改：

- LLM Query Rewrite。
- 前端页面。

交付：

```text
图片画像可写入 pgvector
可按 query embedding 检索候选图
可删除和重建索引
```

## Agent E：RAG 增强

职责：

- `RagQueryRewriteService`。
- `HybridGalleryRetriever`。
- `RagSearchCriteria`。
- `RagCandidate`。
- `RagReranker`。
- `RagContextPacker`。
- `RagTraceService`。
- `RagEvaluationTest`。

依赖：

- Agent D 的 `VectorIndexService`。
- Agent B 的 PostgreSQL repository。

禁止修改：

- ObjectStorageService。
- Spring AI 版本管理。

交付：

```text
LLM rewrite 可降级
Hybrid search 可用
Rule rerank 可解释
Debug trace 返回前端
RAG eval 具备基础指标
```

## Agent F：清理与迁移

职责：

- 清理旧 `knowledge`。
- 清理 `kb-data`。
- 增加迁移脚本。
- 更新文档和启动说明。

依赖：

- Agent B/D/E 完成后再执行。

禁止修改：

- 已稳定的新 RAG 主流程。

交付：

```text
旧入口不会误用
迁移脚本可运行
AGENTS.md / README 更新
```

## 并行开发同步规则

1. 所有 agent 先阅读本方案和 `AGENTS.md`。
2. 公共接口先提交，具体实现可并行。
3. `pom.xml` 由 Agent A 负责，其他 agent 需要依赖时先提出接口需求。
4. `application.yml` 的新增配置必须按命名空间归属：

```text
app.storage
app.rag
app.vector
app.template
spring.datasource
spring.ai
```

5. `PictureApp` 只能由 Agent E 最后集成改造。
6. 旧实现清理由 Agent F 最后进行。
7. 每个 agent 提交前至少跑对应模块单测。
8. 合并到主分支前必须跑：

```text
mvn test
```

9. 数据库相关测试使用 Testcontainers，避免依赖本机数据库状态。

## 推荐开发顺序

```text
1. Agent A：Spring AI 升级
2. Agent B：PostgreSQL schema + repository
3. Agent C：ObjectStorageService local 实现
4. Agent D：pgvector + VectorIndexService
5. Agent E：RAG 增强链路
6. Agent F：旧实现清理和迁移脚本
```

可并行部分：

```text
Agent B 和 Agent C 可并行
Agent E 可先定义接口和 mock 测试
Agent F 只能最后执行
```

## 关键验收场景

### 场景 1：依赖升级后基础对话

```text
POST /chat
mode=chat
```

验收：

```text
非流式可用
流式可用
Advisor 链 order 正确
异常兜底正确
```

### 场景 2：图库落库

```text
上传图片 -> PostgreSQL gallery_picture 有记录 -> 本地文件存在 -> 可读取图片
```

### 场景 3：AI 画像入库和向量入库

```text
POST /profile/{pictureId}/analyze
```

验收：

```text
picture_ai_profile 有记录
picture_vector_index 有向量
vector_status=1
```

### 场景 4：RAG 混合检索

用户输入：

```text
生成一张适合 AI 产品介绍页的 PPT 科技风配图
```

验收：

```text
LLM rewrite 输出 category=ppt
pgvector 召回候选图
metadata filter 过滤无效图
rerank reasons 可解释
返回 ragDebugInfo
```

### 场景 5：Prompt 压缩

选择 3 张显式参考图 + 开启 RAG。

验收：

```text
显式参考图最多 3 张
RAG 参考图最多 5 张
最终上下文不超过配置 max-context-chars
referenceMode=style 时只注入风格相关字段
```

### 场景 6：旧实现清理

验收：

```text
前端不再调用 /knowledge
kb-data 不再参与运行
新图库/RAG 链路完整
```

## 风险与回滚

### Spring AI 升级风险

风险：

```text
Advisor API 变化
VectorStore API 变化
ChatMemory 行为变化
```

回滚：

```text
保留 spring-ai-upgrade 分支
升级前后测试用例保持一致
```

### PostgreSQL 迁移风险

风险：

```text
本地 JSON 数据迁移失败
jsonb 字段映射异常
```

回滚：

```text
保留 JsonFileRepository
通过 profile 切回 local-json
```

### COS 切换风险

风险：

```text
COS 鉴权失败
URL 访问权限配置错误
```

回滚：

```text
ObjectStorageService 切回 local
```

### pgvector 风险

风险：

```text
embedding 维度配置错误
索引重建耗时
相似度阈值需要重新调参
```

回滚：

```text
VectorIndexService 保留 SimpleVectorStore 实现
```

### RAG 增强风险

风险：

```text
LLM rewrite 不稳定
rerank 权重不合理
Prompt 过长
```

回滚：

```text
rewrite 失败降级原 query
rerank 可通过配置关闭
max-context-chars 强制裁剪
```

## 最小可行版本

如果时间有限，建议 MVP 只做：

```text
Spring AI 稳定版 + BOM
PostgreSQL gallery_picture / picture_ai_profile
ObjectStorageService local 实现
pgvector 写入和检索
Hybrid search 基础版
Rule rerank 基础版
RAG debug 返回前端
```

暂缓：

```text
COS 真连接
LLM rewrite 高级 prompt
RAG Evaluation 完整指标
style_template 落库
旧 knowledge 物理删除
```

## 最终目标架构

```text
ChatController
  -> PictureApp
    -> RagQueryRewriteService
    -> HybridGalleryRetriever
      -> PostgreSQL metadata filter
      -> pgvector semantic search
    -> RagReranker
    -> RagContextPacker
    -> PromptReferenceAssembler
    -> ChatClient
    -> ImageGenerationService
    -> RagTraceService

GalleryController
  -> GalleryService
    -> PostgresGalleryPictureRepository
    -> ObjectStorageService

ProfileController
  -> PictureAiProfileService
    -> PostgresPictureAiProfileRepository
    -> VisionAnalysisService
    -> VectorIndexService

Storage:
  PostgreSQL: business data + pgvector
  Redis: chat memory + temporary state
  Local/COS: image files
```
