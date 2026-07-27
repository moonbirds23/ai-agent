# Jina CLIP v2 接入方案

> 目标：在现有文本向量检索之旁，增加图片向量检索能力，实现真正的多模态 RAG（以图搜图、文本搜图双向）。

## 一、现状与目标

```
现状（纯文本）:
  图片 → glm-4.5v → 文字描述 → embedding-2 → 文本向量 → pgvector
  检索: 文本 → embedding-2 → pgvector 文本向量搜

目标（多模态）:
  图片 → glm-4.5v → 文字描述 → Jina CLIP v2 → 文本向量 → pgvector
  图片 → Jina CLIP v2 → 图片向量 → pgvector
  检索: 文本/图片 → Jina CLIP v2 → pgvector 双向量融合搜
```

## 二、模型选型

**Jina CLIP v2** (`jinaai/jina-clip-v2`)

| 属性 | 值 |
|------|-----|
| 调用方式 | REST API（`api.jina.ai/v1/embeddings`） |
| 输入 | text + image（同一请求可混合） |
| 输出 | 1024 维（可截断 768/512） |
| 维度选择 | **768**（中文效果不降，索引体积减半） |
| 免费额度 | 100 万 token/月 |
| 超额费用 | $0.02 / 1M token |
| API 格式 | OpenAI 兼容 JSON |
| 本地备选 | HuggingFace 开源（ONNX Runtime） |

## 三、改动清单

### Phase 1 — 基础设施（后端不可见变化）

#### 1.1 新增 `JinaClipService`

```
src/main/java/com/zzp/aiagent/service/JinaClipService.java        (接口)
src/main/java/com/zzp/aiagent/service/impl/JinaClipServiceImpl.java (实现)
```

- `float[] encodeText(String text)` → 768 维文本向量
- `float[] encodeImage(byte[] imageBytes, String contentType)` → 768 维图片向量
- `List<float[]> encodeBatch(List<ClipInput> inputs)` → 批量编码（减少 API 调用）
- API 调用：POST `https://api.jina.ai/v1/embeddings`，Bearer Token 认证
- 关键参数：`model: "jina-clip-v2"`, `dimensions: 768`, `task: "text_matching"`

#### 1.2 新增 `JinaProperties` 配置

```yaml
# application.yml / application-local.yml
app:
  jina:
    api-key: ${JINA_API_KEY}
    model: jina-clip-v2
    dimensions: 768
    max-batch-size: 16       # 单次请求最多 16 个 input
    timeout-seconds: 30
```

```java
// src/main/java/com/zzp/aiagent/config/JinaProperties.java
@ConfigurationProperties("app.jina")
record JinaProperties(String apiKey, String model, int dimensions,
                      int maxBatchSize, int timeoutSeconds) {}
```

#### 1.3 DB 迁移：`V8__gallery_image_embedding.sql`

```sql
ALTER TABLE picture_ai_profile ADD COLUMN image_embedding vector(768);
-- 允许 NULL，旧数据没有图片向量
-- 后续可以异步回填
```

#### 1.4 `PictureAiProfile` 加字段

```java
// 现有字段不变，增加：
float[] imageEmbedding   // CLIP 图片向量，NULL 表示尚未编码
String embeddingModel    // "jina-clip-v2"
```

#### 1.5 `PictureAiProfileService` 加方法

```java
// 批量回填图片向量（用于历史数据迁移）
int backfillImageEmbeddings(int batchSize);

// 更新单张图片的图片向量
void updateImageEmbedding(Long pictureId, float[] embedding);
```

### Phase 2 — 入库时自动编码

#### 2.1 `PictureAiProfileServiceImpl.analyzeDirectWithBase64()` 增加编码

```java
// 原有：调 glm-4.5v → 文字画像
// 新增：调 jinaClipService.encodeImage() → 图片向量
// 存入 PictureAiProfile.imageEmbedding

public PictureAiProfile analyzeDirectWithBase64(GalleryPicture picture,
        String base64Data, String contentType) {
    // ... 现有文字分析 ...
    
    // 新增：图片向量编码（异步）
    CompletableFuture.runAsync(() -> {
        byte[] imageBytes = Base64.getDecoder().decode(stripPrefix(base64Data));
        float[] embedding = jinaClipService.encodeImage(imageBytes, contentType);
        profileRepository.updateImageEmbedding(picture.id(), embedding);
    }, executor);
    
    // ...
}
```

- 图片向量编码耗时 ~200ms，放到异步线程
- 文本向量仍同步生成（检索需要）

#### 2.2 文本向量模型切换（可选）

- 当前：`embedding-2` 生成文本向量
- 改为：`jinaClipService.encodeText()` 生成文本向量
- **收益**：文本向量和图片向量在同一模型空间，跨模态相似度直接可用
- **风险**：现有索引需重建。**Phase 2 暂不做，Phase 4 评估后切换**

### Phase 3 — 向量索引双存储

#### 3.1 `PgVectorIndexService` 增加图片向量索引

```java
// 现有：只存文本向量
@Override
public void upsert(Long pictureId, float[] textEmbedding) {
    jdbcTemplate.update("""
        INSERT INTO vector_store (picture_id, embedding)
        VALUES (?, ?::vector)
        ON CONFLICT (picture_id) DO UPDATE SET embedding = ?::vector
    """, pictureId, vectorParam(textEmbedding), vectorParam(textEmbedding));
}

// 新增：双向量分开建索引
@Override
public void upsertImageVector(Long pictureId, float[] imageEmbedding) {
    jdbcTemplate.update("""
        INSERT INTO vector_store_image (picture_id, embedding)
        VALUES (?, ?::vector)
        ON CONFLICT (picture_id) DO UPDATE SET embedding = ?::vector
    """, pictureId, vectorParam(imageEmbedding));
}
```

#### 3.2 `VectorIndexService` 接口增加方法

```java
// 图片向量搜索
List<VectorSearchHit> searchByImage(float[] imageEmbedding, int topK, double minScore);
```

#### 3.3 新表 `vector_store_image`

```sql
CREATE TABLE vector_store_image (
    picture_id BIGINT PRIMARY KEY,
    embedding vector(768),
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_vector_store_image_embedding ON vector_store_image
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

### Phase 4 — RAG 检索融合

#### 4.1 `HybridGalleryRetrieverImpl` 增加图片向量查询路径

```java
@Override
public List<RagCandidate> retrieve(RagSearchCriteria criteria) {
    // 现有：向量文本检索
    List<VectorSearchHit> textHits = vectorIndexService.search(query, ...);
    
    // 新增：如果用户传了图片，同时做图片向量检索
    List<VectorSearchHit> imageHits = Collections.emptyList();
    if (criteria.queryImageEmbedding() != null) {
        imageHits = vectorIndexService.searchByImage(
            criteria.queryImageEmbedding(), oversample, minScore);
    }
    
    // 融合：合并两个候选集，加权去重
    Map<Long, RagCandidate> merged = mergeCandidates(
        textHits, criteria.vectorWeight(),
        imageHits, getImageWeight());
    
    // ... 后续 scoring 不变 ...
}
```

#### 4.2 `RagProperties` 增加权重配置

```yaml
app:
  rag:
    weights:
      vector: 0.30        # 文本向量权重（现有）
      keyword: 0.20        # 关键词权重（现有）
      metadata: 0.25       # 元数据权重（现有）
      image-vector: 0.25   # 图片向量权重（新增）
```

#### 4.3 `RagSearchCriteria` 增加字段

```java
float[] queryImageEmbedding  // 用户上传图片的 CLIP 向量（用于以图搜图）
```

### Phase 5 — 工具层暴露

#### 5.1 `GalleryAgentTools` 新增 `searchByImage` 工具

```java
@Tool(description = "以图搜图：用当前上传的图片搜索图库中视觉相似的图片")
public String searchByImage(ToolContext toolContext,
        @ToolParam(description = "搜索关键词（可选，用于过滤结果）") String keyword) {
    // 1. 从 CurrentImageContext 获取当前图片 base64
    // 2. jinaClipService.encodeImage() → embedding
    // 3. HybridGalleryRetriever.retrieveByImage() → 候选
    // 4. 格式化返回
}
```

### Phase 6 — ChatMemory 多模态增强（可选）

#### 6.1 `RedisChatMemory` 外部图片存向量

```java
// 当前 P3 修复后: 立即返回占位文本
// 增强: 异步分析完成后，同时存 CLIP 向量

private ImageRef analyzeExternalImage(Media media) {
    CompletableFuture.runAsync(() -> {
        byte[] bytes = extractBytes(media);
        float[] embedding = jinaClipService.encodeImage(bytes, ...);
        // 存 embedding 到 Redis（新 key 或扩展字段）
    }, executor);
    return ImageRef.textDescription("用户上传了一张图片");
}
```

## 四、改动总览

| 文件 | 操作 | 说明 |
|------|------|------|
| `JinaClipService.java` | **新增** | 接口 |
| `JinaClipServiceImpl.java` | **新增** | 实现（REST Client API 调用） |
| `JinaProperties.java` | **新增** | 配置类 |
| `V8__gallery_image_embedding.sql` | **新增** | DB 迁移 |
| `V9__vector_store_image.sql` | **新增** | 图片向量索引表 |
| `PictureAiProfile.java` | **修改** | 加 imageEmbedding 字段 |
| `PostgresPictureAiProfileRepository.java` | **修改** | SQL 适配新的 vector 列 |
| `PictureAiProfileService.java` | **修改** | 加 updateImageEmbedding() |
| `PictureAiProfileServiceImpl.java` | **修改** | 入库时异步 encode 图片 |
| `VectorIndexService.java` | **修改** | 加 searchByImage() |
| `PgVectorIndexService.java` | **修改** | 实现图片向量索引 |
| `RagSearchCriteria.java` | **修改** | 加 queryImageEmbedding |
| `RagProperties.java` | **修改** | 加 image-vector 权重 |
| `HybridGalleryRetrieverImpl.java` | **修改** | 融合图片向量检索路径 |
| `GalleryAgentTools.java` | **修改** | 新 searchByImage 工具 |
| `application.yml` | **修改** | Jina API Key + 权重配置 |
| `application-local.yml` | **修改** | JINA_API_KEY 占位 |
| `RedisChatMemory.java` | **修改** | 异步存图片向量（可选） |

**新增 3 个文件，修改 13 个文件**。

## 五、不做的

- **不替换现有文本 embedding（embedding-2）**：Phase 2 文本向量保持用智谱，避免重建全部索引。等图片向量路径验证有效后，再评估是否统一用 Jina
- **不引入 ONNX 本地推理**：先用 API，用量超出免费额度或延迟敏感时再考虑本地化
- **不改变前端**：以图搜图的能力对前端透明，用户上传图片后系统自动走图片向量检索

## 六、验证方式

- `JinaClipServiceTest`：encodeText / encodeImage 返回正确维度
- `HybridGalleryRetrieverImplTest`：传图片 embedding 能搜到相似图片
- 手动测试：上传一张小狗图 → searchByImage → 返回图库中所有狗图（无大象）
- 回归：现有 175 个测试全过
