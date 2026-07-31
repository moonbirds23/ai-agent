# Agent 全量修复计划

> 2026-06-05 | 来源：代码审查 (109 Java + 2 HTML) + 前端测试 + CLAUDE.md 已知 Bug

## 总览

| 类别 | 数量 | P0 | P1 | P2 | P3 |
|------|------|----|----|----|----|
| 安全 | 5 | 3 | 1 | 1 | — |
| 稳定性 | 6 | 2 | 1 | 3 | — |
| 正确性 | 5 | 2 | 1 | 1 | 1 |
| 性能 | 5 | — | 4 | 1 | — |
| 模型行为 | 4 | 2 | 2 | — | — |
| 代码质量 | 8 | — | — | 4 | 4 |
| 架构 | 5 | — | 1 | 2 | 2 |
| 前端 | 7 | — | 2 | 4 | 1 |
| 功能 | 3 | — | — | 2 | 1 |
| **合计** | **48** | **9** | **12** | **18** | **9** |

---

## 一、安全（5 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| S1 | **P0** | Pexels `downloadPhoto()` 无 SSRF 校验 | `PexelsPhotoServiceImpl:103` | `URI.create()` 前加 `urlSecurityValidator.validate()` | 1 行 |
| S2 | **P0** | `ChatMediaServiceImpl` 用户 URL 直传 ChatClient → SSRF | `ChatMediaServiceImpl:54` | 外部 URL 先下载验证再传 `ByteArrayResource`，或调用 `urlSecurityValidator` | 10 行 |
| S3 | **P0** | `LocalObjectStorageService` 路径穿越 | `LocalObjectStorageService:35` | `root.resolve(key).normalize()` + startsWith 检查 | 5 行 |
| S4 | P1 | `UrlSecurityValidator` IPv4-mapped IPv6 绕过 | `UrlSecurityValidator.java` | 显式处理 `::ffff:x.x.x.x` 格式 | 5 行 |
| S5 | P2 | 全端点无认证（学习项目暂可接受，加文档标注） | 所有 Controller | 记录在 CLAUDE.md，标注"生产上线前必须加认证" | 文档 |

---

## 二、稳定性（6 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| ST1 | **P0** | `ChatRequest.message` 无 `@Size` → OOM | `ChatRequest.java` | 加 `@Size(max=10000)` | 1 行 |
| ST2 | **P0** | `RateLimitInterceptor` ConcurrentHashMap 永不驱逐 → 内存泄漏 | `RateLimitInterceptor:28` | 换 Caffeine Cache `expireAfterAccess(1h)` 或加定时清理 | 15 行 |
| ST3 | P1 | `GalleryServiceImpl.upload()` base64 解码无大小上限 → OOM | `GalleryServiceImpl:64` | 解码前检查 base64 长度，超限直接拒绝 | 5 行 |
| ST4 | P2 | `ToolProgressContext` 3 个 ConcurrentMap 无 TTL → 内存泄漏 | `ToolProgressContext:33-37` | turnId 加 TTL，`clear()` 已在 finally 调用但异常路径可能漏 | 10 行 |
| ST5 | P2 | `CogViewImageApi` HTTP Client 无连接池配置 | `CogViewImageApi.java` | 配置 `HttpClient` 连接池（maxConnections、keepAlive） | 10 行 |
| ST6 | P2 | `ZhipuVisionAnalysisService` 一次性签名 URL 重试失败 | `ZhipuVisionAnalysisService` | 重试前重新获取签名 URL，或下载图片到内存再分析 | 15 行 |

---

## 三、正确性（5 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| C1 | **P0** | 哈希去重 TOCTOU → 并发重复入库 | `GalleryServiceImpl:69-76` | `pic_hash` 加 `UNIQUE` 约束 + catch `DuplicateKeyException` 回退 | DB 迁移 + 10 行 |
| C2 | **P0** | Redis push+trim 非原子 → 并发消息丢失 | `RedisChatMemory:137-140` | Lua 脚本 `EVAL "RPUSH ... LTRIM ..." ` 原子执行 | 15 行 |
| C3 | P1 | `ConversationLimitService` count-then-check TOCTOU | `ConversationLimitServiceImpl:31` | Redis `INCR` + TTL 原子计数 | 10 行 |
| C4 | P2 | `RedisChatMemory.count()` 只查 Redis 不查 PG → 限流绕过 | `RedisChatMemory:170` | count 方法增加 PG 回退，或 `ConversationLimitService` 改查 PG | 10 行 |
| C5 | P3 | `GlobalExceptionHandler` 缺 5 种 Spring 异常处理 | `GlobalExceptionHandler.java` | 加 handler：`HttpMessageNotReadableException`、`MethodNotSupported`、`MediaTypeNotSupported`、`ConstraintViolation`、`MissingParam` | 30 行 |

---

## 四、性能（5 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| P1 | P1 ✅ | **N+1 查询** — `HybridGalleryRetrieverImpl` 最多 100 次 SQL | `HybridGalleryRetrieverImpl:65-93` | 先收集所有 pictureId → 批量 `listByIds()` + `listByPictureIds()` → 内存 join | 30 行 |
| P2 | P1 ✅ | `@Transactional` 内调 AI API → 持 DB 连接数秒 | `GalleryServiceImpl:97-114` | AI 分析移到事务外：`analyzeDirectWithBase64` 加 `@Transactional(propagation=NEVER)` | 5 行 |
| P3 | P1 ✅ | `RedisChatMemory.add()` 内同步调视觉模型 → 阻塞请求线程 2~5s | `RedisChatMemory:291-313` | 外部图片先用 `TEXT_DESCRIPTION` 占位文本，异步分析后回填 | 30 行 |
| P4 | P1 | `ExplicitReferenceResolver` N 次 `getByPictureId()` 而非批量 | `ExplicitReferenceResolver:52` | 改用 `profileService.listByPictureIds()` | 5 行 |
| P5 | P2 | `RagQueryRewriteServiceImpl` CompletableFuture 用 ForkJoinPool → 超时后线程残留 | `RagQueryRewriteServiceImpl` | 注入专用 `Executor` 替代 `ForkJoinPool.commonPool()` | 5 行 |

---

## 五、模型行为（4 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| M1 | **P0** | 模型不调工具却声称完成（幻觉） | `system.st` | 强化约束：❌绝对禁止 + Few-shot 反例 | prompt |
| M2 | **P0** | 多轮相似请求后"偷懒"不调工具 | `system.st` | 加"每轮独立原则"：即使前几轮做过类似的事，本轮也必须重新调工具 | prompt |
| M3 | P1 | ImageGuard 拦截消息不友好（不区分"工具失败"和"工具没调"） | `ChatServiceImpl:228-248` | 区分两种情况：①工具调了但失败 → "搜索未找到结果，建议换词" ②根本没调 → "检测到回复未经验证，已拦截，请重新发送" | 20 行 |
| M4 | P1 | System prompt 缺少"Pexels 文字元数据已足够构造 prompt，不需额外分析"指引 | `system.st` | 加：Pexels 结果的 `alt` 描述已包含主体/色彩/构图，可直接用于构造 `generateImage` prompt，无需额外调 `analyzeImage` | prompt |

---

## 六、代码质量（8 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| Q1 | P2 | 6 处 String 常量应改 enum | 见下方明细 | 创建 enum + 改字段类型 + 适配序列化 | 批量 |
| Q2 | P2 | 死代码清理（5 处） | 见下方明细 | 删除/注释 | 删 |
| Q3 | P2 | `CogViewImageApi` 命名误导（实际是 ImageDownloadService） | `CogViewImageApi.java` | 重命名为 `HttpImageDownloadService` | 重命名 |
| Q4 | P2 | `SpringAiAiInvoke` 无意义命名 | `SpringAiAiInvoke.java` | 重命名或合并到其他 Controller | 重命名 |
| Q5 | P3 | `StorageLocation` 是 class 伪枚举 | `StorageLocation.java` | 改为 `enum StorageLocation { MAIN, CACHE }` | 5 行 |
| Q6 | P3 | `RagContextPackerImpl` else 分支内永假条件 | `RagContextPackerImpl:106-118` | 删除冗余 `"overall"` 分支 | 3 行 |
| Q7 | P3 | `PromptTemplate` 死代码 `Map.of()` | `PromptTemplate:77` | 删除未使用变量 | 1 行 |
| Q8 | P3 | `ChatServiceImpl` 未使用常量 `MAX_IMAGE_BYTES`/`ALLOWED_IMAGE_TYPES` | `ChatServiceImpl:65-66` | 删除或在对应位置使用 | 1 行 |

**Q1 枚举化明细：**

| 类 | 字段 | 改法 |
|----|------|------|
| `ImageRef` | `type: String` | `enum ImageRefType { GALLERY, TEXT_DESCRIPTION }` |
| `MessageRecord` | `role: String` | `enum MessageRole { USER, ASSISTANT, SYSTEM }` |
| `ChatRequest` | `mode: String` | `enum ChatMode { CHAT, IMAGE_ANALYSIS, IMAGE_GENERATION }` |
| `PictureAiProfile` | `vectorStatus: Integer` | `enum VectorStatus { PENDING, INDEXED, FAILED }` |
| `GalleryPicture` | `storageLocation: String` | 改为 `StorageLocation` enum 类型 |
| `RagSearchCriteria` / `GalleryQueryRequest` | `referenceMode: String` | `enum ReferenceMode { OVERALL, STYLE, COLOR, COMPOSITION }` |

---

## 七、架构（5 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| A1 | P1 | Phase C（TaskLedger/TaskVerifier/ResponseComposer）已实现但未接入 | `ChatServiceImpl` | 在 `chat()`/`chatStream()` 响应路径中接入验收 | 30 行 |
| A2 | P2 | `RagServiceImpl.buildContext()` God Class | `RagServiceImpl.java` | 拆分为独立 Stage：`QueryRewriteStage` → `RetrieveStage` → `RerankStage` → `PackStage`，`RagPipeline` 做编排 | 重构 |
| A3 | P2 | 工具注册硬编码在 `ChatServiceImpl` 构造函数 | `ChatServiceImpl:93-101` | `Agent` 构造函数改为 `List<Object> tools`，由 Spring 注入所有 `@Tool` bean | 10 行 |
| A4 | P3 | `PictureApp` 废弃壳层 | `PictureApp.java` | 删除，确认零引用后移除 | 删 |
| A5 | P3 | SSE 事件组装逻辑混在 `ChatServiceImpl.chatStream()` 150 行 | `ChatServiceImpl:139-212` | 抽取 `StreamEventAssembler` 组件 | 重构 |

---

## 八、前端（7 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| F1 | P1 | `uploading` 变量未声明 → 并发上传绕过 | `index.html:982` | 在状态区加 `let uploading = false;` | 1 行 |
| F2 | P1 | `newSession()` 不清除 `selectedReferences` | `index.html:397-401` | 加 `selectedReferences = []; useGalleryRag = false;` | 3 行 |
| F3 | P2 | `analyzePicture()` 依赖全局 `event` 对象 | `index.html:894` | 改为 `event` 参数传递 | 5 行 |
| F4 | P2 | `doImportUrl()` 对 `@NotBlank name` 发 `null` → 400 | `index.html:1058` | `name: name || '导入-' + Date.now()` | 1 行 |
| F5 | P2 | SSE 无自动重连 + 无超时 | `index.html:650` | 加 `AbortController` 30s 超时 + 断线提示重试按钮 | 20 行 |
| F6 | P2 | `allCategories` 跨页面搜索无限增长 | `gallery.html:196` | 改为每次搜索前清空或用 Set 去重 | 5 行 |
| F7 | P3 | `index.html` 无指向 `gallery.html` 的导航链接 | `index.html` header | 加导航按钮"📁 图库管理" | 3 行 |

---

## 九、功能（3 项）

| ID | 优先级 | 问题 | 位置 | 修复方式 | 量 |
|----|--------|------|------|---------|----|
| FT1 | P2 | `category` 单值 → 多分类 | DB + GalleryPicture + 前后端 | V7 Flyway 加 `categories JSONB`，前端分类多选 | 见 plan |
| FT2 | P2 | 向量检索不准（搜小狗出大象） | `HybridGalleryRetrieverImpl` | 关键词打分扩展到 `introduction` + `tags` + AI 画像字段；`min-score` 0.4→0.45 | 20 行 |
| FT3 | P3 | `gallery.html` 分类管理面板 | `gallery.html` | 创建/删除分类 UI + 图片分配分类 | 100 行 |

---

## 执行顺序建议

```
第1天（P0·9项）：
  安全 S1~S3 + 稳定性 ST1~ST2 + 正确性 C1~C2 + 模型 M1~M2

第2天（P1·12项）：
  安全 S4 + 稳定性 ST3 + 正确性 C3 + 性能 P1~P4 + 模型 M3~M4 + 架构 A1 + 前端 F1~F2

第3天（P2·18项）：
  安全 S5 + 稳定性 ST4~ST6 + 正确性 C4 + 性能 P5 + 代码 Q1~Q4 + 架构 A2~A3 + 前端 F3~F6 + 功能 FT1~FT2

后续（P3·9项）：
  代码 Q5~Q8 + 架构 A4~A5 + 前端 F7 + 功能 FT3

---

## 本回合修复记录

| ID | Bug | 修复方式 |
|----|-----|----------|
| P1 | N+1 查询: retrieve() 50 hits → 100 SQL | stream().map(pictureId).distinct() → listByIds() + listByPictureIds() 批量加载 → Map 内存 join |
| P2 | @Transactional 内调 AI 持 DB 连接 | TransactionSynchronization + afterCommit + CompletableFuture.runAsync 异步执行 |
| P3 | add() 内同步调视觉模型阻塞 | analyzeExternalImage 改为 CompletableFuture.runAsync + 立即返回占位文本 |
| M1 | 图库搜索两套代码: searchByKeyword(SQL LIKE) vs HybridRetriever(向量+关键词) | 合并为 GalleryService.search() → 委托 HybridGalleryRetrieverImpl.retrieve()；删 searchByKeyword |
| M2 | Agent 无图库管理工具: 无法编辑/删除图片 | 新增 updatePictureMetadata / deletePicture 两个 @Tool + GalleryService.update() + GalleryPicture.withMeta() |
```
