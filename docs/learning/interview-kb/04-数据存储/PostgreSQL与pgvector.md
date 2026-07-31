# PostgreSQL 与 pgvector

## 数据库迁移（Flyway）

6 个迁移脚本，渐进式 DDL 变更：

| 迁移 | 内容 | 关键变更 |
|------|------|----------|
| V1 | 创建 `gallery_picture` 表 | 24字段，含 tags(JSONB)、pic_size、pic_width/height/scale、favorited、软删除(is_delete) |
| V2 | 创建 `picture_ai_profile` 表 | picture_id(UNIQUE)、subject/scene/style/colors/composition/lighting/mood、index_text、vector_status |
| V3 | 创建 `chat_message` 表 + 索引 | conversation_id/role/content/image_refs(JSONB)/metadata(JSONB)、复合索引 (conversation_id, created_at) |
| V4 | gallery_picture 加列 | `storage_location VARCHAR(16) DEFAULT 'MAIN'` |
| V5 | gallery_picture 加列 | `pic_hash VARCHAR(64)` — SHA-256 哈希去重 |
| V6 | gallery_picture 改列类型 | url/thumbnail_url VARCHAR(1024) → TEXT — CogView 签名 URL 超长 |

## gallery_picture 表 (V1)

```sql
CREATE TABLE gallery_picture (
    id BIGSERIAL PRIMARY KEY,
    url VARCHAR(1024),              -- → V6 改为 TEXT
    thumbnail_url VARCHAR(1024),    -- → V6 改为 TEXT
    name VARCHAR(255) NOT NULL,
    introduction TEXT,
    category VARCHAR(64),
    tags JSONB,                     -- Jackson 序列化 List<String>
    pic_size BIGINT,
    pic_width INTEGER, pic_height INTEGER, pic_scale DOUBLE PRECISION,
    pic_format VARCHAR(32),
    user_id BIGINT DEFAULT 1,       -- 单用户模式
    space_id BIGINT DEFAULT 0,
    review_status INTEGER DEFAULT 1,
    pic_color VARCHAR(64),
    source_type VARCHAR(64),        -- "upload" / "import_url"
    favorited BOOLEAN DEFAULT FALSE,
    create_time TIMESTAMP DEFAULT now(),
    update_time TIMESTAMP DEFAULT now(),
    is_delete INTEGER DEFAULT 0     -- 软删除
);
```

软删除策略：`is_delete=0` → 正常，`is_delete=1` → 已删除。所有查询都带 `WHERE is_delete=0`。

## picture_ai_profile 表 (V2)

```sql
CREATE TABLE picture_ai_profile (
    id BIGSERIAL PRIMARY KEY,
    picture_id BIGINT NOT NULL UNIQUE,  -- 一对一关联 gallery_picture
    subject TEXT, scene TEXT, style TEXT,
    colors TEXT, composition TEXT, lighting TEXT, mood TEXT,
    image_prompt TEXT,
    index_text TEXT,                    -- 向量索引文本
    vector_status INTEGER DEFAULT 0,    -- 0=未索引, 1=已索引, -1=失败
    analyzed_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT now(),
    update_time TIMESTAMP DEFAULT now()
);
```

**index_text 构建逻辑**：拼接 GalleryPicture 的 name/introduction/category/tags + VisionAnalysis 的 subject/scene/style/colors/composition/lighting/mood/imagePrompt。每条约 500~2000 字。这段文本被智谱 embedding-2 编码为 1024 维向量写入 pgvector。

## chat_message 表 (V3)

```sql
CREATE TABLE chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,       -- USER / ASSISTANT / SYSTEM
    content TEXT,
    image_refs JSONB,                -- List<ImageRef> Jackson 序列化
    metadata JSONB,                  -- 扩展元数据
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_chat_message_conv_time ON chat_message (conversation_id, created_at);
```

**复合索引 (conversation_id, created_at)** 支持按会话+时间排序查询消息列表。TEXT 类型无长度限制（适用于 AI 生成的图片分析描述）。

## pgvector 配置

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: true       # 自动建表/索引
        index-type: ivfflat           # IVF 倒排索引
        distance-type: cosine_distance
        dimensions: 1024              # embedding-2 输出维度
        schema-name: public
        table-name: picture_vector_store
```

**Phase 1 修复**: `VectorStoreConfig` 维度从硬编码 2048 → 读取 `spring.ai.vectorstore.pgvector.dimensions` 配置（1024），与 embedding-2 输出对齐。

## PG 默认化后的 DataSource 策略

```java
// PostgresAutoConfig.java
@Configuration
@Profile("!test")
@ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
public class PostgresAutoConfig {}
```

**为什么不直接在 `@SpringBootApplication` 上 exclude？**
因为 `@SpringBootApplication(exclude=...)` 将类加入全局排除列表，即使后续用 `@ImportAutoConfiguration` 也无法重新导入。分离到单独的 `@Configuration` + `@Profile("!test")` 可以在 test profile 下通过 `application-test.yml` 的 `spring.autoconfigure.exclude` 排除 PG。

## 哈希去重

上传/导入前计算 SHA-256：

```java
String picHash = sha256(bytes);
List<GalleryPicture> existing = repository.findByHash(picHash);
if (!existing.isEmpty()) {
    return existing.get(0);  // 直接返回已有记录，跳过上传
}
```

避免同一张图多次上传浪费存储空间。

### SQL 分页 (Phase 3)

`GalleryService.listAll()` 从全量内存分页改为 SQL 层分页：

- 新增 `findAllPaged(offset, limit, keyword, ...)` → SQL `WHERE ... ORDER BY ... LIMIT :limit OFFSET :offset`
- 新增 `countFiltered(...)` → SQL `SELECT COUNT(*)`
- 新增 `GalleryPageResult { records, total, page, pageSize }`
- `pageSize` 上限 100

### INSERT ON CONFLICT (Phase 1)

`PostgresPictureAiProfileRepository.save()` 从"先查后插"改为 `INSERT ... ON CONFLICT (picture_id) DO UPDATE`，消除并发 double-INSERT 竞态。

### GalleryPicture 静态工厂 (Phase 3)

`GalleryPicture` 23 参数构造器 → 静态工厂方法：

| 方法 | 用途 |
|------|------|
| `forUpload(name, ..., picHash)` | 上传/导入时创建新记录 |
| `withUrl(url)` | 回写对象存储 URL |
| `withFavorite(boolean)` | 切换收藏状态 |
