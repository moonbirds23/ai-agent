# V1 README — 云图库 AI 图片生成助手

## 版本范围

基准提交 `c290edc` → 当前工作区 V1。

## 一句话定位

DeepSeek 文本理解 + 智谱 CogView-4 生图 + 智谱 GLM-4.5V 视觉分析的三状态 AI 图片生成助手。后续迁移融合到 Picture-Backend。

## 三条核心链路

| 模式 | 入口方式 | 行为 |
|------|---------|------|
| 文本交流 | 输入文字，发送 | DeepSeek 理解需求 → 整理结构化 Prompt → 前端展示 |
| 图片分析 | 上传图片 → 点"分析图片" | 智谱 GLM-4.5V 提取视觉元素 → 输出结构化 Prompt → 写入会话记忆 |
| 图片生成 | 开启"生成模式" → 发送 | DeepSeek 整理 Prompt → CogView-4 生图 → 返回图片 + 下载链接 |

## 项目结构

```
com.zzp.aiagent
  ├── common/             BaseResponse / ResultUtils / ThrowUtils / PromptTemplate
  ├── exception/          ErrorCode (int码) + BusinessException + GlobalExceptionHandler
  ├── image/              生图 + 视觉分析 + 图片下载代理
  ├── model/dto/          按业务分子包（chat / image / memory）
  ├── model/vo/           ChatResponseVO / StreamEventVO
  ├── controller/         ChatController / HealthController / ImageController
  ├── app/                PictureApp（三模式路由 + 流式/非流式双路径）
  ├── advisor/            4 自定义 + 1 内置 MessageChatMemory
  └── memory/             RedisChatMemory（media 序列化）
```

## 后端核心能力

### Advisor 拦截链
ContentGuard(0) → MessageChatMemory(内置) → PromptOptimize(20) → Logging(30) → ExceptionGuard(MAX)

- **ContentGuard**：消息非空/长度 + 图片数量/大小/格式校验
- **PromptOptimize**：提示词模板化改写（StringTemplate）
- **Logging**：记录原始输入、改写 Prompt、回复内容、字符数、耗时
- **ExceptionGuard**：BusinessException → 友好兜底回复

### 异常体系
- `ErrorCode` int 码枚举（0 成功 / 40xxx 客户端 / 50xxx 服务端）
- `BusinessException` 统一业务异常（替代旧版 5 类异常）
- `GlobalExceptionHandler` 统一拦截，返回 `BaseResponse`
- `ThrowUtils.throwIf()` 断言工具，统一异常抛出入口

### 响应规范
- `BaseResponse<T>` 统一包装（code + data + message）
- `ResultUtils.success/error` 构建响应
- Controller 返回 `BaseResponse<VO>`，HTTP 状态码始终 200
- DTO/VO 用 record，BaseResponse 用 @Data

### 图片服务
- **生图**：`ImageGenerationService` 接口 + `ZhipuImageGenerationService`（CogView-4）
- **视觉分析**：`VisionAnalysisService` 接口 + `ZhipuVisionAnalysisService`（GLM-4.5V）
- **下载代理**：`/api/image/download`，RemoteImageDownloadService（SSRF 白名单防护）
- 默认 Noop 实现用于测试和降级

### 会话记忆
- Redis 持久化（`RedisChatMemory`），替代 InMemoryChatMemory
- `MessageRecord` 支持 `mediaUrls` 字段，多模态消息可追溯

### 提示词模板
- `PromptTemplate` + `prompts/default/*.st`（StringTemplate 引擎）
- 系统提示词、Prompt 优化模板分离

## 前端（静态单页）

- `GET /api/` → `index.html`
- 三种入口按钮：普通发送 / 分析图片 / 生成模式开关
- 图片上传 + 预览
- SSE 流式逐字输出
- 生成图片展示 + 下载按钮
- 生成完成后展开"本次使用的 Prompt"（可收起）
- 图片分析结果完整展示视觉元素

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 非流式对话/生图/分析（JSON） |
| POST | `/api/chat/stream` | SSE 流式对话/生图/分析 |
| GET | `/api/image/download` | 后端代理下载远程图片 |
| GET | `/api/health` | 健康检查 |
| GET | `/api/doc.html` | Knife4j 接口文档 |

### ChatRequest 字段
```
message, chatId, mode (chat/image_generation/image_analysis),
imageBase64, imageUrl, generationMode (兼容旧版)
```

### ChatResponseVO type
```
chat / image_ready / image_analyzed / image_generated
```

### SSE 事件类型
```
chatId / token / done / progress / error
```

## 模型分工

| 模型 | 职责 |
|------|------|
| DeepSeek | 文本理解、Prompt 整理与二次优化 |
| 智谱 GLM-4.5V | 图片内容识别、视觉元素提取 |
| 智谱 CogView-4 | 根据最终 Prompt 生成图片 |

## 关键设计决策

1. **生成 ≠ 保存**：生图只返回临时 URL，入库由用户明确触发（后续迁移实现）
2. **视觉分析独立**：不上传图片到 DeepSeek，独立建设智谱视觉分析链路
3. **显式模式 + 后端状态机**：当前三种显式入口，后端 `mode` 字段路由；后续收敛为 Agent 自动路由
4. **高成本动作确认**：生图仅用户明确进入"生成模式"才触发

## 测试

- 87 个单元测试（Advisor 链 / 流式路径 / 多模态 / 图片校验 / 多轮对话 / Redis 记忆 / 异常处理）
- AI Bean 标注 `@Profile("!test")`，test profile 排除真实 API 调用
- 旧 DeepSeek 视觉联调测试标记为手动（与独立视觉分析架构方向相反）

## 技术栈

Spring Boot 3.5.14 / Java 21 / Spring AI 1.0.0-M6 / Redis / Maven / 智谱 API

## 启动

```bash
# 编译（必须指定 JDK 21，系统 JDK 是 1.8）
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn compile

# 启动（local profile 加载 API Key）
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn spring-boot:run -Dspring-boot.run.profiles=local

# 测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test
```

端口 `8231`，context-path `/api`，Swagger `/api/doc.html`。

## 已知问题

| 问题 | 现象 | 缓解 |
|------|------|------|
| 流式 ExceptionGuard 兜底不生效 | BusinessException 在流式路径穿透 ExceptionGuard | PictureApp.doChatStream() 手动 onErrorResume |
| 关键词过滤仅有字面匹配 | 绕过黑名单的违规内容无法拦截 | 后续接语义审核模型 |
| PromptOptimize 无输入时模型幻觉 | 空描述时 DeepSeek 随机猜测主题 | 后续检测有效需求描述 |
