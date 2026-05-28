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

云图库 AI 图片生成助手。全链路已切换智谱（`glm-4-flash` 文本 + `glm-4.5v` 视觉 + `embedding-2` 嵌入 + CogView 生图）。已完成对话链路、Advisor 拦截链、三层 RAG 增强（显式参考图 → 收藏图检索 → 风格模板兜底）、图库管理、图片 AI 画像、前端调试台。后续迁移融合到 Picture-Backend。

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
  ├── gallery/          图库管理（单用户模式，JSON 文件存储）
  │     ├── model/GalleryPicture.java     图片元数据 record
  │     ├── GalleryService.java          上传/导入/分页/收藏/删除
  │     └── JsonFileGalleryPictureRepository.java  → gallery-data/pictures.json
  ├── profile/          图片 AI 画像
  │     ├── model/PictureAiProfile.java  视觉分析结果 + indexText + vectorStatus
  │     └── PictureAiProfileService.java 分析/索引/删除
  ├── rag/              三层 RAG 增强
  │     ├── model/RagContext.java        三层上下文对象
  │     ├── ExplicitReferenceResolver.java  Layer 1 显式参考图解析
  │     ├── GalleryRagRetriever.java       Layer 2 向量检索
  │     ├── RagServiceImpl.java           Layer 3 模板兜底编排
  │     └── PromptReferenceAssembler.java Prompt 装配 + 调试数据构建
  ├── template/         系统风格模板
  │     ├── model/StyleTemplate.java     模板 record
  │     └── StyleTemplateService.java    关键词匹配
  └── knowledge/        旧版知识库模块（V1，将被 gallery/profile/rag 替代）
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
| **adviseContext 不能传 null** | M6 强制非 null 校验，构造 AdvisedResponse 时必须有 `new HashMap<>()` |
| **onErrorResume 顺序** | 流式异常处理必须 `.onErrorResume(BusinessException.class, ...)` 在前、`.onErrorResume(e -> ...)` 在后，否则子类被父类吞掉 |
| **test profile 隔离** | AI 相关 Bean（PictureApp、ChatController 等）标注 `@Profile("!test")`，test profile 排除 `OpenAiAutoConfiguration` |
| **MessageAggregator 路径** | 不在 advisor 包下，全限定名 `org.springframework.ai.chat.model.MessageAggregator` |
| **API Key 不入库** | 通过环境变量 `ZHIPU_API_KEY` + `application-local.yml`（gitignored）注入 |
| **BaseResponse 反序列化** | 必须保留 `@NoArgsConstructor`，否则 Jackson 无法反序列化 |
| **用户消息模板不要包含 JSON Schema** | `{outputFormat}` 在 user message 中被替换为 JSON Schema 后，Spring AI 的 StringTemplate4 会把 Schema 中的 `{}` 当模板语法解析崩溃。输出格式约束只能放在 system prompt 中 |
| **SimpleVectorStore 内存持久化** | 向量在内存中，`@PreDestroy` 时才通过 `VectorStorePersistence` 写入 `kb-data/vector-store.json`。服务被 `taskkill` 强杀时数据丢失 |
| **ImageIO 读不了 webp** | `javax.imageio.ImageIO` 不支持 webp 格式，`GalleryServiceImpl.upload()` 中的宽高检测对 webp 图片返回 0x0 |
| **智谱 embedding-2 相似度偏低** | 语义相关内容余弦相似度通常 0.4~0.5，`GalleryRagRetriever.MIN_SCORE` 设 0.4 较为合理，0.65 过严会导致大量漏召回 |
| **RAG Prompt 写入 ChatMemory** | 当前 RAG 增强后的完整 Prompt 通过 `chatClient.prompt().user()` 传入，会被 `MessageChatMemoryAdvisor` 自动存入记忆。方案设计要求"RAG 上下文只作为本次调用临时 Prompt，不写入 ChatMemory"，当前违反此约束 |
| **新旧模块共存** | `knowledge/`（V1，`kb-data/`）和 `gallery/profile/rag`（V2，`gallery-data/`）两套模块同时存在。V1 的 `KnowledgeService` 已从 PictureApp 移除但模块文件未删。VectorStore 由旧 `KnowledgeConfig` 创建，被新 `GalleryRagRetriever` 和 `PictureAiProfileService` 共用 |

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
- [x] 向量持久化（SimpleVectorStore + kb-data/vector-store.json）
- [x] 单元测试 87 个
- [ ] `saveGeneratedToGallery` 实现（生图成功后保存到图库，当前只有 TODO）
- [ ] `referenceMode` 实际应用（overall/style/color/composition 裁剪参考图字段）
- [ ] RAG Prompt 不写入 ChatMemory（当前违反设计约束）
- [ ] 真实生图 API 接入（DALL·E / SD，接口已预留）
- [ ] 会话记忆持久化（当前 InMemory，重启丢失）
- [ ] 旧 knowledge/ 模块清理（V1，已被 gallery/profile/rag 替代）
