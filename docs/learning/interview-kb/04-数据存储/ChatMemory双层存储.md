# ChatMemory 双层存储

## 架构

```
┌──────────────────────────────────────┐
│  Redis（热层 - 短期快速访问）          │
│  Key: "chat:memory:{conversationId}" │
│  数据结构: List (RPUSH + LRANGE)     │
│  TTL: 7 天 (可配置 ttl-days)         │
│  截断: 最近 50 条 (可配置 max-messages)│
│  取数: LRANGE key (len-N) (len-1)    │
└──────────────┬───────────────────────┘
               │ 异步回写 (CompletableFuture.runAsync)
               │ 写失败不阻塞主流程
┌──────────────▼───────────────────────┐
│  PostgreSQL（冷层 - 长期完整历史）      │
│  表: chat_message                    │
│  字段: conversation_id / role /      │
│        content / image_refs(JSONB) / │
│        metadata(JSONB) / created_at  │
│  索引: (conversation_id, created_at) │
└──────────────────────────────────────┘
```

## 读写策略

### 读取路径

```
chatMemory.get(conversationId)
  ├─ ① Redis: LRANGE key (len-50) (len-1)  → 命中最新的消息
  │     → 反序列化 JSON → List<MessageRecord> → toMessages()
  │
  │     **Phase 1 新增**: `RedisChatMemory.count(conversationId)` — 返回 Redis List 长度，替代异步 PG 计数做会话上限判断。
  │
  ├─ ② Redis 未命中 → PG 回源
  │     → chatHistoryRepo.findByConversation(conversationId, 50)
  │     → 反序列化 → List<Message>
  │     → 写回 Redis（预热下次读取）
  │
  └─ ③ PG 也未命中 → 返回空列表
```

### 写入路径

```
chatMemory.add(conversationId, messages)
  ├─ ① 图片引用处理: toRecord() → imageRefs (GALLERY / TEXT_DESCRIPTION)
  ├─ ② 同步写 Redis: RPUSH + TRIM(-maxMessages, -1) + EXPIRE(7天)
  └─ ③ 异步写 PG: CompletableFuture.runAsync()
       → 每条约 1 次 INSERT
       → 失败不阻塞，只记 warn 日志

       **Phase 1 修复**: `CompletableFuture.runAsync()` 改为注入 `@Qualifier("taskExecutor") Executor` 专用线程池，避免占用 ForkJoinPool.commonPool。
```

## 图片记忆分流

图片引用在 ChatMemory 中分两种类型存储：

| 类型 | 存储方式 | 还原方式 |
|------|---------|---------|
| GALLERY | `ImageRef(type="GALLERY", pictureId=123)` | 从对象存储下载图片二进制 → 构建 Media |
| TEXT_DESCRIPTION | `ImageRef(type="TEXT_DESCRIPTION", description="故宫角楼，对称构图...")` | 拼接为文字: "用户此前发过图片，描述：..." |

**分流逻辑**：在 `toRecord()` 中通过 `resolveImageRef(media)` 判断：
- Media 的 URL 匹配图库路径 `/gallery/files/{id}` → 存 GALLERY 引用
- 非图库来源（base64/外部URL）→ 调视觉模型分析 → 存 TEXT_DESCRIPTION

## 配置项

```yaml
app:
  chat-memory:
    max-messages: 50             # Redis List 最多保留条数
    ttl-days: 7                  # Redis Key 过期天数
    max-conversation-messages: 200  # 单会话消息总数上限
```

## 会话窗口限制

ChatServiceImpl.chat() 入口调用 `checkConversationLimit(chatId)`：

```java
int count = chatHistoryRepo.countByConversation(chatId);
if (count >= 200) → throw BusinessException("会话消息已达上限，请开启新会话");
```

**设计意图**：单会话消息不无限增长。200 条是 PG 的硬限制，不仅 Redis（50条），也防止 PG 查询变慢。

## chat_memory_retrieve_size 的修复

Spring AI 1.0.0 GA 的 `MessageChatMemoryAdvisor` 移除了 `chatMemoryRetrieveSize()` 配置项。原本通过 `.param("chat_memory_retrieve_size", 50)` 传参但在 GA 版本不生效。

**修复方案**：在 `RedisChatMemory.get()` 内部用 Redis 的 `LRANGE key (len-N) (len-1)` 在 Redis 层截断，而不是在 MCMA 层截断。这样无论 MCMA 要多少条，Redis 最多返回 `maxMessages` 条。
