# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## MD 记录准则

核心原则：只写 AI 读代码发现不了的东西。`CLAUDE.md` 是给 AI 的开发手册，不是项目目录；写约定、写坑、写进度、写理由。

### 必写

| 类别 | 写什么 | 示例 |
|------|--------|------|
| 技术选型理由 | 为什么选这个库/框架 | 为什么使用某个 ORM / AI SDK / 存储方案 |
| 编码规范 | 全局约定，违反会出 Bug | 返回值统一包装 `BaseResponse`，异常统一用 `ErrorCode` |
| 已知的坑 | 踩过的雷，防止再犯 | 系统 JDK 是 1.8，Maven 命令必须指定 JDK 21 |
| 当前进度 | 哪些做完了、哪些待做 | `[x] 对话链路` / `[ ] 云图库保存` |

### 选写

| 类别 | 写什么 |
|------|--------|
| 常用命令 | 启动、测试、打包等高频命令 |
| 项目定位 | 一句话说清项目是什么 |

### 不写

| 不写什么 | 为什么 |
|----------|--------|
| 包名、类名、文件路径 | AI 扫代码即可知道 |
| API 接口列表、表字段、方法签名 | 代码本身就是文档 |
| 已完成的实现方案/设计文档 | 过时快，留了反而误导 |

### 更新时机

- 项目定位变了 → 更新定位
- 加了新模块 → 更新进度
- 换了技术方案 → 更新技术栈/选型理由
- 踩了新坑 → 补充注意事项

不需要更新：新增方法、加字段、改 Bug 逻辑——AI 读代码就能感知。

## 项目定位

云图库 AI 图片生成助手。全链路已切换智谱（`glm-4-flash` 文本 + `glm-4.5v` 视觉 + `embedding-2` 嵌入 + CogView 生图）。Spring AI 已升级 1.0.0 GA（2025-05-20 发布），M6→GA API 破坏性迁移已完成（Advisor/ChatMemory/ChatClient 全部适配）。已引入 PostgreSQL（含 Flyway 迁移）+ pgvector 向量库 + 对象存储抽象（local/COS 双实现）。后续迁移融合到 Picture-Backend。

**编码规范对齐 Picture-Backend**（`D:\code\java\Picture-Backend`），后续两个项目将代码迁移融合上线。

## 环境

- Spring Boot 3.5.14 + Java 21
- 构建工具：Maven（`mvn`），系统 JDK 1.8，需指定 `JAVA_HOME="D:/develop/java/JDK/jdk-21"`
- 端口 `8231`，context-path `/api`

## 常用命令

```bash
# 编译（必须指定 JAVA_HOME，系统 JDK 是 1.8）
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn compile

# 启动（local profile 加载 application-local.yml 中的 API Key）
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn spring-boot:run -Dspring-boot.run.profiles=local

# 运行测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test

# 排查端口占用
netstat -ano | grep 8231
taskkill //F //PID <PID>
```

## 项目结构与分层规范

```
com.zzp.aiagent
  ├── common/           基础设施（对齐 Picture-Backend common/）
  │     ├── BaseResponse.java     全局响应封装（@Data + Serializable）
  │     ├── ResultUtils.java      响应构建工具（success/error）
  │     ├── ThrowUtils.java       断言工具（throwIf）
  │     └── PromptTemplate.java   提示词模板引擎
  ├── exception/        异常体系（对齐 Picture-Backend exception/）
  │     ├── ErrorCode.java        错误码枚举（int 码）
  │     ├── BusinessException.java 统一业务异常
  │     └── GlobalExceptionHandler.java
  ├── image/            图片生成服务（接口 + Noop 占位）
  ├── model/
  │     ├── dto/        请求 DTO（按业务分子包）
  │     │     ├── chat/ChatRequest.java     （含 RAG 字段：referencePictureIds/useGalleryRag/...）
  │     │     ├── image/ImageAgentResponse.java（AI 层结构化输出）
  │     │     ├── image/ImageGenerationResult.java
  │     │     └── memory/MessageRecord.java （含 mediaUrls）
  │     └── vo/         响应 VO
  │           ├── ChatResponseVO.java       （含 ragDebugInfo Object 字段）
  │           └── StreamEventVO.java        （含 progress 事件）
  ├── controller/       REST 接口（Chat/Gallery/Profile/Template/Health）
  ├── app/              应用层（PictureApp，RAG 增强生图链路）
  ├── advisor/          Advisor 拦截链
  ├── memory/           会话记忆（RedisChatMemory，含 media 序列化）
  ├── gallery/          图库管理（单用户模式，JSON/PG 双存储）
  │     ├── model/GalleryPicture.java     图片元数据 record (23字段)
  │     ├── model/StorageLocation.java    存储位置常量 MAIN/CACHE
  │     ├── model/GalleryUploadRequest.java
  │     ├── GalleryService.java          上传/导入/分页/收藏/删除
  │     ├── GalleryProperties.java        @ConfigurationProperties("app.gallery")
  │     ├── GalleryCacheCleanupTask.java  @Scheduled 缓存图库定时清理
  │     ├── PostgresGalleryPictureRepository.java  @Profile("postgres") + @Primary
  │     └── JsonFileGalleryPictureRepository.java  @Profile("!test & !postgres")
  ├── profile/          图片 AI 画像
  │     ├── model/PictureAiProfile.java  视觉分析结果 + indexText + vectorStatus
  │     └── PictureAiProfileService.java 分析/索引/删除
  ├── storage/          对象存储抽象（Phase 3）
  │     ├── ObjectStorageService.java    upload/download/delete/getUrl
  │     ├── LocalObjectStorageService.java  @Profile("!cos") 本地文件存储
  │     ├── CosObjectStorageService.java    @Profile("cos") COS 预留
  │     └── StorageProperties.java         @ConfigurationProperties("app.storage")
  ├── vector/            向量索引抽象（Phase 4）
  │     ├── VectorIndexService.java      upsert/delete/search
  │     ├── SimpleVectorIndexService.java  @Profile("!postgres") 基于 SimpleVectorStore
  │     └── PgVectorIndexService.java      @Profile("postgres") 基于 PgVectorStore
  ├── rag/              三层 RAG 增强
  │     ├── enhanc/                      RAG 增强链路接口（Phase 5）
  │     │     ├── RagQueryRewriteService.java  LLM Query 改写
  │     │     ├── HybridGalleryRetriever.java  混合检索
  │     │     ├── RagReranker.java             规则重排序
  │     │     ├── RagContextPacker.java        上下文压缩
  │     │     └── RagTraceService.java         调试追踪
  │     ├── model/RagContext.java        三层上下文对象
  │     ├── ExplicitReferenceResolver.java  Layer 1 显式参考图解析
  │     ├── GalleryRagRetriever.java       Layer 2 向量检索
  │     ├── RagServiceImpl.java           Layer 3 模板兜底编排
  │     └── PromptReferenceAssembler.java Prompt 装配 + 调试数据构建
  ├── template/         系统风格模板
  │     ├── model/StyleTemplate.java     模板 record
  │     └── StyleTemplateService.java    关键词匹配
```

### 核心分层规则

1. **DTO 用 record，BaseResponse 用 @Data**：DTO/VO 用 Java record（简洁，自动 Serializable）；`BaseResponse` 是框架层类，用 `@Data + @NoArgsConstructor + implements Serializable`（保证 Jackson 序列化兼容）
2. **Controller 只透传 + 包装**：Controller 直接返回 `BaseResponse<VO>`，通过 `ResultUtils.success(app.doChat(...))` 包装。Controller 不做 AI 层转换
3. **App/Service 层返回裸 VO**：`PictureApp.doChat()` 返回 `ChatResponseVO`（不包装 BaseResponse）。内部完成 `ImageAgentResponse → ChatResponseVO.objToVo()` 转换。`BaseResponse` 只在 Controller 层使用
4. **异常统一走 BusinessException**：所有业务异常用 `new BusinessException(ErrorCode.XXX)` 或 `new BusinessException(ErrorCode.XXX, "详情")`

## 响应规范

### BaseResponse —— 统一响应包装

```java
@Data
@NoArgsConstructor
public class BaseResponse<T> implements Serializable {
    private int code;       // 0=成功，40xxx=客户端错误，50xxx=服务端错误
    private T data;         // 响应数据（错误时为 null）
    private String message; // "ok" / 错误信息
}
```

### ResultUtils —— 响应构建

```java
ResultUtils.success(data)              // → {"code":0, "data":..., "message":"ok"}
ResultUtils.error(ErrorCode.XXX)       // → {"code":40000, "data":null, "message":"..."}
ResultUtils.error(code, "message")     // → {"code":xxx, "data":null, "message":"..."}
ResultUtils.error(ErrorCode, "自定义") // → {"code":xxx, "data":null, "message":"自定义"}
```

### Controller 方法签名

```java
// 非流式
@PostMapping
public BaseResponse<ChatResponseVO> chat(@Valid @RequestBody ChatRequest request)

// 流式 SSE
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<StreamEventVO> chatStream(@Valid @RequestBody ChatRequest request)

// 健康检查
@GetMapping
public BaseResponse<String> healthCheck()
```

**关键约束：**
- Controller 返回 `BaseResponse<VO>`，不返回 `ResponseEntity`，不设 `@ResponseStatus`
- HTTP 状态码始终 200，业务状态在 JSON `code` 字段
- 入参共用 `@Valid @RequestBody`，不通过 URL 传消息内容

## 异常规范

### ErrorCode —— int 码枚举

| 码段 | 含义 | 示例 |
|------|------|------|
| 0 | 成功 | `SUCCESS(0, "ok")` |
| 40000 | 参数错误（通用） | `PARAMS_ERROR(40000, "请求参数错误")` |
| 40100 | 输入校验 | `EMPTY_MESSAGE(40100)` `MESSAGE_TOO_LONG(40101)` |
| 40200 | 内容安全 | `CONTENT_BLOCKED(40200)` |
| 50000~50003 | AI 调用 | `AI_AUTH_FAILED(50000)` `AI_RATE_LIMIT(50001)` |
| 50010 | 记忆 | `MEMORY_ERROR(50010)` |
| 59999 | 系统兜底 | `SYSTEM_ERROR(59999)` |

### BusinessException

唯一的业务异常类，替代旧版 `AiAgentException`/`InvalidInputException`/`ContentSafetyException`/`AiApiException`/`ChatMemoryException`：

```java
throw new BusinessException(ErrorCode.EMPTY_MESSAGE);
throw new BusinessException(ErrorCode.CONTENT_BLOCKED, "消息包含违规词汇: xxx");
```

### GlobalExceptionHandler

```java
@ExceptionHandler(BusinessException.class)
public BaseResponse<?> handleBusiness(BusinessException e)   // → ResultUtils.error(e.getCode(), e.getMessage())

@ExceptionHandler(Exception.class)
public BaseResponse<?> handleException(Exception e)         // → ResultUtils.error(SYSTEM_ERROR, "系统错误")
```

- 返回 `BaseResponse<?>`，不做 `@ResponseStatus`
- `BusinessException` 的 `message` 直接透传给客户端

### ThrowUtils —— 断言（统一异常抛出入口）

```java
ThrowUtils.throwIf(condition, ErrorCode.XXX);
ThrowUtils.throwIf(condition, ErrorCode.XXX, "详情");
```

**规范：业务校验一律用 `ThrowUtils.throwIf()`，不直接 `new BusinessException(...)`。**
这样统一了异常抛出风格，代码意图更明确（"条件成立则抛异常"），也避免了遗漏 import BusinessException。

## Advisor 规范

### 执行顺序

```
ContentGuard(0) → MessageChatMemory(内置) → PromptOptimize(20) → Logging(30) → ExceptionGuard(MAX)
```

### Advisor 不可变 Record 约束

`AdvisedRequest` 和 `AdvisedResponse` 是 Java Record，修改 `adviseContext` 必须复制—修改—重建：

```java
Map<String, Object> ctx = new HashMap<>(request.adviseContext());
ctx.put("key", value);
return AdvisedRequest.from(request).adviseContext(ctx).build();
```

`Builder.adviseContext(null)` 会抛 `IllegalArgumentException`，必须显式传 `new HashMap<>()`。

### 异常处理

Advisor 内抛 `BusinessException` → `ExceptionGuardAdvisor`（order=MAX）兜底转友好回复：
- 非流式：`try-catch (BusinessException e)` → `friendlyResponse(e.getMessage())`
- 流式：`.onErrorResume(BusinessException.class, ...)` → 必须在 `.onErrorResume(e -> ...)` 之前

## 已知的坑

| 坑 | 说明 |
|----|------|
| **系统 JDK 是 1.8** | 所有 Maven 命令必须加 `JAVA_HOME="D:/develop/java/JDK/jdk-21"`，否则编译失败 |
| **Spring AI 1.0.0 GA API 破坏性变化** | M6→GA 全量重命名：`CallAroundAdvisor`→`CallAdvisor`，`AdvisedRequest`→`ChatClientRequest`，`AdvisedResponse`→`ChatClientResponse`，链方法 `nextAroundCall`→`nextCall`，`MessageChatMemoryAdvisor` 构造器→Builder 模式，`InMemoryChatMemory`→`MessageWindowChatMemory`+`InMemoryChatMemoryRepository`，`Media` 移到 `org.springframework.ai.content`，`CallAdvisorChain`/`StreamAdvisorChain` 不再是函数式接口（多了 `getCallAdvisors()`/`getStreamAdvisors()` 方法），`ChatMemory` 新增 `get(String)` 抽象方法 |
| **onErrorResume 顺序** | 流式异常处理必须 `.onErrorResume(BusinessException.class, ...)` 在前、`.onErrorResume(e -> ...)` 在后，否则子类被父类吞掉 |
| **test profile 隔离** | AI 相关 Bean 标注 `@Profile("!test")`，test profile 需排除 DataSource/Flyway/OpenAI 全家桶自动配置，并设 `spring.ai.openai.api-key: test-fake-key` |
| **ChatClientMessageAggregator 替代 MessageAggregator** | 1.0.0 GA 新增 `ChatClientMessageAggregator`（`spring-ai-client-chat`），`MessageAggregator`（`spring-ai-model`）降级为处理裸 `ChatResponse`。LoggingAdvisor 已迁移到新 API |
| **Chain 不再是函数式接口** | `CallAdvisorChain`/`StreamAdvisorChain` 在 1.0.0 各有 2 个抽象方法，测试中不能再 `chain -> response` lambda，须用 `mock(CallAdvisorChain.class)` |
| **JDBC 依赖触发 DataSource 自动配置** | 加入 `spring-boot-starter-jdbc` 后需在 test profile 排除 `DataSourceAutoConfiguration` + `FlywayAutoConfiguration`，否则容器启动失败 |
| **PostgreSQL profile 策略** | `postgres` profile 激活 `Postgres*Repository`（@Primary 覆盖 JsonFile），PgVectorStore 覆盖 SimpleVectorStore。默认 profiles（无 postgres）走 local JSON + SimpleVectorStore |
| **API Key 不入库** | 通过环境变量 `ZHIPU_API_KEY` + `application-local.yml`（gitignored）注入 |
| **BaseResponse 反序列化** | 必须保留 `@NoArgsConstructor`，否则 Jackson 无法反序列化 |
| **用户消息模板不要包含 JSON Schema** | `{outputFormat}` 在 user message 中被替换为 JSON Schema 后，Spring AI 的 StringTemplate4 会把 Schema 中的 `{}` 当模板语法解析崩溃。输出格式约束只能放在 system prompt 中 |
| **SimpleVectorStore 内存持久化** | 向量在内存中，`@PreDestroy` 时才通过 `VectorStorePersistence` 写入 `data/vector-store.json`。服务被 `taskkill` 强杀时数据丢失 |
| **ImageIO 读不了 webp** | `javax.imageio.ImageIO` 不支持 webp 格式，`GalleryServiceImpl.upload()` 中的宽高检测对 webp 图片返回 0x0 |
| **智谱 embedding-2 相似度偏低** | 语义相关内容余弦相似度通常 0.4~0.5，`GalleryRagRetriever.MIN_SCORE` 设 0.4 较为合理，0.65 过严会导致大量漏召回 |
| **RAG Prompt 写入 ChatMemory** | 当前 RAG 增强后的完整 Prompt 通过 `chatClient.prompt().user()` 传入，会被 `MessageChatMemoryAdvisor` 自动存入记忆。方案设计要求"RAG 上下文只作为本次调用临时 Prompt，不写入 ChatMemory"，当前违反此约束 |
| **RAG 增强链 Profile 策略** | 5 个 enhance 实现类中，`HybridGalleryRetrieverImpl` 是 `@Profile("postgres")`（依赖 pgvector），其余 4 个是 `@Profile("!test")`。`RagServiceImpl` 通过 `ObjectProvider<T>` 注入所有增强组件，检测到全部就绪时走增强路径，否则走原三层 RAG 降级。测试中需 mock 5 个 ObjectProvider 并设 `@MockitoSettings(strictness = Strictness.LENIENT)` 避免 UnnecessaryStubbing |
| **GalleryPicture 23 字段 record** | 加字段影响 N 处 `new GalleryPicture(...)` 构造点（Service/Repository/测试）。record 有 compact constructor：`if (storageLocation == null) storageLocation = StorageLocation.MAIN`，旧 JSON 数据无此字段反序列化为 null 后自动兜底 |
| **@EnableScheduling 已启用** | `AiAgentApplication` 已加 `@EnableScheduling`，`GalleryCacheCleanupTask` 通过 `@Scheduled(cron)` 每日清理过期缓存图。test profile 通过 `@Profile("!test")` 排除定时任务，无需额外配置 |

## 多模态架构

### ChatRequest 字段

```java
public record ChatRequest(
    @NotBlank String message,        // 用户消息
    String chatId,                   // 会话ID
    Boolean generationMode,          // true=生成模式，false/null=讨论模式
    String imageBase64,              // 图片base64（与imageUrl互斥）
    String imageUrl                  // 图片URL（与imageBase64互斥）
) {}
```

### 双路径分流

```
Flag OFF（讨论模式）               Flag ON（生成模式）
─────────────────                 ─────────────────
ContentGuard(文本+图片校验)        ContentGuard
  → MCMA(注入历史消息)               → MCMA(retrieveSize=50)
  → PromptOptimize(改写userText)     → PromptOptimize
  → Logging                          → Logging
  → ExceptionGuard                   → ExceptionGuard
  → 返回ChatResponseVO               → ImageGenerationService.generate()
                                      → ChatResponseVO.imageGenerated()
```

### 图片串联链路

1. 前端 FileReader → base64 → `ChatRequest.imageBase64`
2. `PictureApp.buildUserSpec()` → `Media(MimeType, ByteArrayResource)` → `ChatClient.user(spec)`
3. `ContentGuard.validateImages()` 校验数量/大小
4. `PromptOptimizeAdvisor.enhance()` 不改写 image，只改写 userText
5. `RedisChatMemory.toRecord()` 从 `UserMessage.getMedia()` 提取 URL → `MessageRecord.mediaUrls`
6. `RedisChatMemory.toMessage()` 从 `mediaUrls` 重建 `Media` → `new UserMessage(text, mediaList)`

### ImageGenerationService 接口

```java
public interface ImageGenerationService {
    ImageGenerationResult generate(String prompt, String style, String dimensions);
    String getProviderName();
}
```

默认 `NoopImageGenerationService` 抛 `IMAGE_GENERATION_FAILED`。真实实现用 `@Primary` 覆盖。

## 已知 Bug（待修）

| Bug | 现象 | 根因 | 当前缓解 |
|-----|------|------|----------|
| **流式 ExceptionGuard 兜底不生效** | 敏感词等 BusinessException 在流式路径穿透 ExceptionGuard，直达 PictureApp | Spring AI M6 `DefaultAroundAdvisorChain` 的 Micrometer Observation scope 阻断 `.onErrorResume()` 信号 | `PictureApp.doChatStream()` 的 `.onErrorResume()` 区分 BusinessException（透传具体消息）和未知异常（泛化提示） |
| **关键词过滤仅有字面匹配** | "继续我上一个任务"可绕过黑名单，模型仍生成违规内容 | `ContentGuardAdvisor` 用 `List.of("暴力","色情","政治敏感")` 做字符串匹配，无语义理解 | 暂无，后续需接 DeepSeek 或独立安全模型做语义审核 |
| **PromptOptimizeAdvisor 无输入时模型幻觉** | 用户发"继续上一个任务"、空描述等无头指令时，DeepSeek 随机猜测主题 | 模型在缺少上下文时自行推测，非记忆污染 | 暂无，后续可在 PromptOptimize 中检测有效需求描述 |
| **多模态记忆序列化丢失图片引用** | RedisChatMemory 只有 `instanceof String` 分支提取 mediaUrls，但 `Media.getData()` 实际类型是 `URI`（URL传图）或 `ByteArrayResource`（base64传图），两者都不匹配 → mediaUrls 恒为 null；且 `LocalObjectStorageService.getUrl()` 返回 `/api/gallery/files/{id}` 相对路径，`toMessage()` 中 `new URL(relativePath)` 直接抛 MalformedURLException | `RedisChatMemory.java:90` `toRecord()` 的 `filter(m -> m.getData() instanceof String)` 遗漏了 URI 和 ByteArrayResource 两种类型；`LocalObjectStorageService.java:70-76` `getUrl()` 未补全 baseUrl | 图片分析模式走手动 `chatMemory.add()` 只存纯文本，绕开了此 Bug；已通过 ImageRef 分类型存储（GALLERY/TEXT_DESCRIPTION）修复 |
| **chat_memory_retrieve_size 不生效** | `PictureApp` 中 `.param("chat_memory_retrieve_size", 10/50)` 传参无效，MCMA 每次从 `ChatMemory.get()` 取全部消息全部注入 Prompt，消息越多 token 消耗越大 | Spring AI 1.0.0 GA 的 `MessageChatMemoryAdvisor` Builder 没有 `chatMemoryRetrieveSize()` 方法，类中也不存在 `CHAT_MEMORY_RETRIEVE_SIZE` 常量——该参数在 GA 版本被移除了，MCMA 的 `before()` 方法不对消息列表做截断 | `RedisChatMemory.get()` 内部改用 `LRANGE key (len-N) (len-1)` 只取最后 N 条，从 Redis 网络层截断

## 当前进度

- [x] 智谱全链路切换（`glm-4-flash` 文本 + `glm-4.5v` 视觉 + `embedding-2` 嵌入 + CogView 生图）
- [x] Advisor 拦截链（4 个自定义 + 1 个内置）
- [x] 多轮对话（InMemoryChatMemory + MessageChatMemoryAdvisor）
- [x] 流式 SSE + 非流式双接口（均为 POST + @RequestBody）
- [x] 结构化输出（BeanOutputConverter + ImageAgentResponse）
- [x] 统一响应规范（BaseResponse + ResultUtils + ThrowUtils，对齐 Picture-Backend）
- [x] 异常体系（ErrorCode int 码 + BusinessException + GlobalExceptionHandler）
- [x] 提示词模板化（PromptTemplate + prompts/*.st）
- [x] 多模态支持（图片输入 + 生成模式分流 + 前端 toggle）
- [x] 图片校验（ContentGuard 扩展：数量/大小/格式）
- [x] ChatMemory 支持 media（MessageRecord.mediaUrls + RedisChatMemory 序列化）
- [x] 图库管理（上传/URL导入/分页/收藏/删除，JSON 文件存储到 gallery-data/）
- [x] 图片 AI 画像（VisionAnalysis → indexText → SimpleVectorStore 索引）
- [x] 三层 RAG 增强（显式参考图 → 收藏图检索 → 风格模板兜底）
- [x] 系统风格模板（10 套预设，关键词匹配，`style-templates.yml`）
- [x] 前端三栏调试台（图库管理/对话生图/参考调试面板）
- [x] 向量持久化（SimpleVectorStore + data/vector-store.json）
- [x] 单元测试 167 个（含 80 个新增 RAG 核心链路测试）
- [ ] `saveGeneratedToGallery` 实现（生图成功后保存到图库，当前只有 TODO）
- [ ] `referenceMode` 实际应用（overall/style/color/composition 裁剪参考图字段）
- [ ] RAG Prompt 不写入 ChatMemory（当前违反设计约束）
- [ ] 真实生图 API 接入（DALL·E / SD，接口已预留）
- [ ] 会话记忆持久化（当前 InMemory，重启丢失）
- [x] 旧 knowledge/ 模块清理
- [x] **Spring AI 1.0.0 GA 升级**（M6→GA，BOM 统一依赖管理，Advisor/ChatMemory/ChatClient API 全量迁移）
- [x] **PostgreSQL 落库**（Flyway V1-V2 迁移脚本，`PostgresGalleryPictureRepository` + `PostgresPictureAiProfileRepository`，`@Profile("postgres")` 切换）
- [x] **对象存储抽象**（`ObjectStorageService` 接口，`LocalObjectStorageService` 默认实现，`CosObjectStorageService` 预留，`GalleryServiceImpl` 已重构）
- [x] **pgvector + VectorIndexService**（`VectorIndexService` 抽象，`SimpleVectorIndexService` / `PgVectorIndexService` 双实现，`PictureAiProfileServiceImpl` + `GalleryRagRetriever` 已用新接口）
- [x] **RAG 增强接口体系**（`rag/enhance/` 包：QueryRewrite / HybridRetrieve / Rerank / ContextPack / Trace，接口+Record 已定义）
- [x] **RAG 增强链路完整实现**（`RagQueryRewriteServiceImpl` LLM改写 + `HybridGalleryRetrieverImpl` 混合检索 + `RagRerankerImpl` 加权重排序 + `RagContextPackerImpl` referenceMode裁剪+字数截断 + `RagTraceServiceImpl` 追踪日志，`RagServiceImpl` 已集成，postgres profile 激活增强路径，默认 profile 走原三层 RAG 降级）
- [x] **图片记忆分流存储**（图库图片存 GALLERY:pictureId 引用，外部图片调视觉模型分析后存 TEXT_DESCRIPTION 文字；Media MimeType 写死 PNG 改为动态解析）
- [x] **ChatMemory 双层存储**（Redis 短期记忆 + PostgreSQL chat_message 完整历史，`V3__chat_message.sql` + `ChatHistoryRepository` + `JdbcChatHistoryRepository` + `NoopChatHistoryRepository`）
- [x] **chat_memory_retrieve_size 修复**（Spring AI 1.0.0 GA MCMA 无截断能力，改为 `RedisChatMemory.get()` 内部使用 Redis LRANGE 负索引只取最后 N 条）
- [x] **参考图数量限制 ≤ 3**（`ChatRequest.referencePictureIds` 加 `@Size(max=3)`，Jakarta 校验在 Controller 层拦截）
- [x] **图库存储位置 + 缓存图库**（`StorageLocation` 常量类 MAIN/CACHE，`GalleryPicture.storageLocation` 字段，`GalleryProperties` 配置，`GalleryCacheCleanupTask` 定时清理过期缓存图，V4 Flyway 迁移）
- [x] **对话图片自动入库**（`PictureApp.autoSaveToCacheGallery()` → 图片自动以 CACHE 位置入库，`buildUserSpec()` 用入库 URL 构造 Media（ChatMemory 可识别为 GALLERY），入库失败降级为直发 bytes）
- [x] **生成模式当前图片补发**（`handleGeneration/handleGenerationStream` 原本丢弃当前图片，现通过 `buildGenerationUserSpec()` 将入库后的原图 Media 挂到生成 Prompt 上）
- [x] **会话窗口消息数限制**（`ChatMemoryProperties.maxConversationMessages` 默认 200，`PictureApp` 入口调用 `ChatHistoryRepository.countByConversation()` 校验，超限抛 BusinessException）
- [x] **Redis List trim 截断**（`RedisChatMemory.add()` 末尾 `trim(key, -maxMessages, -1)` 确保列表不无限增长）

## 测试分类

测试按 `@Tag` 分为两类，按模块分子包：

### 分类

| 标签 | 范围 | 特征 |
|------|------|------|
| `@Tag("unit")` | 纯单元测试 | 无 Spring 容器、无 Mockito stub，纯逻辑验证 |
| `@Tag("integration")` | 集成测试 | 使用 `@ExtendWith(MockitoExtension.class)` + `@Mock` 隔离外部依赖 |

### 模块分布

| 包 | 类型 | 测试数 | 覆盖内容 |
|----|------|--------|----------|
| `advisor/` | unit + integration | ~30 | ContentGuard/PromptOptimize/Logging/ExceptionGuard 非流式+流式+图片 |
| `app/` | integration | ~20 | PictureApp 流式/多模态/多轮/视觉模型 |
| `common/` | unit | 9 + 10 | BaseResponse 序列化 + PromptTemplate 模板引擎 |
| `exception/` | unit | ~5 | GlobalExceptionHandler |
| `memory/` | unit | ~5 | RedisChatMemory + MessageRecord |
| `model/` | unit | ~5 | StreamEventVO + DTO 序列化 |
| **`rag/`** (新增) | unit + integration | **68** | RagContext 模型 + PromptReferenceAssembler 装配 + RagService 编排 + ExplicitReferenceResolver 解析 + GalleryRagRetriever 检索 |
| **`template/`** (新增) | unit | **13** | StyleTemplateService 关键词匹配/编码查找/全量列出 |

### RAG 测试专项（核心链路覆盖）

```
RagContextTest (unit)
  ├── empty() → 三层全空, isEmpty=true
  ├── addExplicit → 追加/去null/多张
  ├── addRetrieved → 与explicit隔离
  ├── withTemplate → 设置模板
  ├── isEmpty → 三层组合判断(4种)
  └── ReferencePicture → 组合/无profile

PromptReferenceAssemblerTest (unit)
  ├── assemble() → 空上下文/仅explicit/仅retrieved/仅template/三层全有/无画像
  ├── buildDebugInfo() → 空/有参考图/有模板
  └── buildDebugData() → enhancedPrompt/retrieved含id+name/无名回退/无画像无style键/模板字段

RagServiceImplTest (integration, mock三层依赖)
  ├── Layer1: 指定IDs→调resolver / 结果入上下文
  ├── Layer2: useGalleryRag(null/true/false) / 检索结果入上下文 / 空消息跳过
  ├── Layer3: L1+L2空→触发 / L1有→短路 / L2有→短路 / 显式code→getByCode
  └── Combined: L1+L2同时存在+L3短路 / message=null跳过L2+L3

ExplicitReferenceResolverTest (integration, mock图库+画像)
  └── resolve(): 完整数据 / 无画像→stub / 部分ID不存在→跳过 / GalleryService异常降级

GalleryRagRetrieverTest (integration, mock向量存储+图库+画像)
  ├── retrieve(): 检索解析 / 无匹配 / null/blank查询 / 多条全部返回 / VectorStore异常降级 / 图库记录缺失
  └── docId解析: "pic-42"→42 / 非pic-前缀→跳过 / "pic-abc"→跳过

StyleTemplateServiceTest (unit)
  ├── listAll(): 全量5模板
  ├── getByCode(): 存在/不存在
  └── match(): 精确/多命中/无关/null/空/双向子串/最高分胜出
```

### 运行命令

```bash
# 全量测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test

# 仅单元测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test -Dgroups="unit"

# 仅集成测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test -Dgroups="integration"

# 仅 RAG 模块
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test -Dtest="com.zzp.aiagent.rag.*"

# 仅模板模块
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test -Dtest="com.zzp.aiagent.template.*"
```
