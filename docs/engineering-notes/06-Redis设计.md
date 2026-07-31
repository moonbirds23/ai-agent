# 06 — Redis 设计（ChatMemory 双层存储）

## 为什么需要 Redis？

1. **AI 对话需要上下文**：多轮对话中，每轮都要把历史消息发给 AI，否则 AI 不知道刚才说了什么
2. **读写频率极高**：每轮对话都要读历史 + 写新消息，数据库扛不住这个频率
3. **数据有时效性**：7 天前的对话基本不会再引用，不需要永久保存
4. **需要持久化备份**：Redis 重启数据丢失，需要 PG 做冷备份

## 双层存储架构

```
写路径:
  ChatMemory.add(conversationId, message)
    │
    ├─→ Redis (同步，主存储)
    │     RPUSH chat:memory:{conversationId} <序列化的MessageRecord>
    │     LTRIM chat:memory:{conversationId} -{maxMessages} -1   ← 截断
    │     EXPIRE chat:memory:{conversationId} {ttlSeconds}       ← 续期
    │
    └─→ PostgreSQL (异步，备份)
          CompletableFuture.runAsync(() -> {
            chatHistoryRepository.save(conversationId, message)
          })

读路径:
  ChatMemory.get(conversationId, retrieveSize)
    │
    ├─→ Redis (优先)
    │     LRANGE chat:memory:{conversationId} -{retrieveSize} -1
    │     有数据 → 直接返回
    │
    └─→ PostgreSQL (回源，Redis 空时)
          chatHistoryRepository.findByConversation(conversationId, retrieveSize)
          有数据 → 写回 Redis → 返回
```

## 图片记忆分流

这是本项目的核心创新点。图片体积大（base64 几百KB~几MB），不能直接存 Redis。

### 存储策略

```
Message 包含 Media（图片）
      │
      ▼
  resolveImageRef(media)
      │
      ├─ 图库图片 (URL 匹配 /gallery/files/{pictureId})
      │     → ImageRef.gallery(pictureId)
      │     → Redis/DB 只存引用 "GALLERY:123"
      │
      └─ 外部图片 (http URL / base64 / 本地文件)
            → VisionAnalysisService.analyze(image)
            → ImageRef.textDescription("这是一张...的描述")
            → Redis/DB 存文字描述 "TEXT_DESCRIPTION:这是一张..."
```

### 还原策略

```
读取消息时:
      │
      ├─ GALLERY:pictureId
      │     → GalleryService.getById(pictureId)
      │     → ObjectStorageService.download(key)
      │     → 重建 Media 对象 (含图片bytes + MimeType)
      │
      └─ TEXT_DESCRIPTION:description
            → 拼接到消息文本末尾
            → "[图片描述: 这是一张...]"
```

## Redis 数据结构

```
Key:   chat:memory:{conversationId}
Type:  List
Value: JSON 序列化的 MessageRecord[]

MessageRecord {
  role: "user" | "assistant" | "system",
  content: "消息文本",
  imageRefs: [
    { type:"GALLERY", pictureId:123 },
    { type:"TEXT_DESCRIPTION", description:"这是一张..." }
  ]
}
```

## 配置参数

```yaml
app:
  chat-memory:
    max-messages: 50        # Redis List 最大长度
    ttl-days: 7             # Redis Key 过期时间
    max-conversation-messages: 200  # 单会话消息总数上限（PG层面）
```

## 关键设计决策

### 1. 为什么 Redis List 而不是 String？

- List 天然有序，支持范围查询（LRANGE）
- 支持裁剪（LTRIM），自动淘汰旧消息
- 不需要额外的排序字段

### 2. 为什么异步写 PostgreSQL？

- 不阻塞主链路（对话响应延迟优先）
- PG 写失败不影响对话功能（Redis 是主存储）
- 极端情况下 PG 数据可能落后几条消息，可接受

### 3. 为什么不直接用 Spring AI 的 InMemoryChatMemory？

- 应用重启数据丢失，用户体验差
- 无持久化，无法做数据分析/审计
- 不支持分布式部署

## 已知 Bug（已修复）

**问题**：`RedisChatMemory.toRecord()` 中 `filter(m -> m.getData() instanceof String)` 遗漏了 URI 和 ByteArrayResource 两种 Media 数据类型，导致 imageRefs 恒为 null。

**修复**：改为 `resolveImageRef(URI)` 匹配图库路径 → `GALLERY:pictureId`；外部图片调视觉分析 → `TEXT_DESCRIPTION`。

**问题**：Spring AI 1.0.0 GA 的 MCMA 不支持 `chatMemoryRetrieveSize`，导致每次注入全部历史消息。

**修复**：`RedisChatMemory.get()` 内部使用 `LRANGE key -N -1` 只取最后 N 条。
