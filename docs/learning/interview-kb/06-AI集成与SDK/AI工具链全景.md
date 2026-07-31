# AI 工具链全景

> 日期: 2026-06-03

## 总览

项目以 **Spring AI 1.0.0 GA** 为 AI 编排框架，以 **智谱（Zhipu）** 为全链路模型供应商，配合 PostgreSQL + pgvector 向量库、Redis 记忆存储、Resilience4j 容错熔断，形成完整的 AI 图片生成助手。

```
                    ┌─────────────────────────────┐
                    │     Spring AI 1.0.0 GA       │
                    │  (Advisor链 / ChatMemory /   │
                    │   ChatClient / 结构化输出)     │
                    └──────────┬──────────────────┘
                               │ OpenAI 兼容协议
                    ┌──────────▼──────────────────┐
                    │     智谱 (Zhipu)             │
                    │  ┌──────────────────────┐    │
                    │  │ glm-4-flash  文本模型  │    │
                    │  │ glm-4.5v     视觉模型  │    │
                    │  │ embedding-2  嵌入模型  │    │
                    │  │ CogView-4    生图API  │    │
                    │  └──────────────────────┘    │
                    └──────────┬──────────────────┘
                               │
          ┌────────────────────┼────────────────────┐
          │                    │                    │
  ┌───────▼──────┐  ┌─────────▼────────┐  ┌───────▼──────┐
  │  PostgreSQL   │  │     Redis        │  │ Resilience4j │
  │  + pgvector  │  │  ChatMemory      │  │ 熔断+重试     │
  │  向量库+图库  │  │  会话记忆存储     │  │ AI调用容错    │
  └──────────────┘  └──────────────────┘  └──────────────┘
```

---

## 1. Spring AI 1.0.0 GA —— AI 编排框架

### 1.1 定位与版本

| 项 | 值 |
|----|-----|
| 版本 | 1.0.0 GA（2025-05-20 发布） |
| BOM | `spring-ai-bom:1.0.0`（统一依赖管理） |
| 协议兼容 | OpenAI 协议（`spring-ai-starter-model-openai`） |
| 实际调用 | 智谱 API（`base-url: https://open.bigmodel.cn/api/paas/v4`） |

### 1.2 核心组件及在本项目中的用途

| Spring AI 组件 | 本项目用途 |
|----------------|-----------|
| **ChatClient** | 核心对话引擎，Builder 模式装配 defaultAdvisors + defaultSystem |
| **ChatModel** (`openAiChatModel`) | OpenAI 协议兼容的模型调用入口，对接智谱 `glm-4-flash` |
| **Advisor 链** (`CallAdvisor` / `StreamAdvisor`) | 5 个自定义 Advisor + 1 个内置 MCMA，形成拦截器管道 |
| **ChatMemory** (`RedisChatMemory`) | 多轮对话记忆，Redis 存储 + 图片引用解析 |
| **MessageChatMemoryAdvisor** | Spring AI 内置 Advisor，before() 注入历史消息，after() 存当前消息 |
| **BeanOutputConverter** | 结构化输出：将模型回复约束为 `ImageAgentResponse` JSON Schema |
| **EmbeddingModel** | 文本向量化，对接智谱 `embedding-2`（1024 维） |
| **PgVectorStore** | pgvector 向量存储，IVFFlat 索引 + cosine_distance |
| **Media** | 多模态图片载体，支持 URI / ByteArrayResource 等多种数据源 |

### 1.3 Advisor 链设计

```
order=0:  ContentGuard      → 输入校验（消息/关键词/图片）
order=内置: MCMA             → ChatMemory 注入/存储历史消息
order=15: RagInjection      → RAG 增强文本注入 Prompt（在 MCMA 之后！）
order=20: PromptOptimize    → 模板渲染 + Prompt 优化
order=30: Logging           → 请求/响应日志
order=MAX: ExceptionGuard   → 异常兜底转友好消息
```

**关键设计**：RagInjection(15) 在 MCMA(内置) 之后执行——MCMA 存入 ChatMemory 的是原始消息，RAG 增强文本不会污染记忆。

### 1.4 BeanOutputConverter 结构化输出

```java
// ChatServiceImpl 构造函数
this.outputConverter = new BeanOutputConverter<>(ImageAgentResponse.class);
String systemPrompt = promptTemplate.render("default", "system",
    "outputFormat", outputConverter.getFormat());
// → {outputFormat} 被替换为 JSON Schema，约束模型输出固定结构
```

---

## 2. 智谱（Zhipu）—— 全链路模型供应商

### 2.1 四种模型及用途

| 模型 | 协议路径 | 用途 | 调用方式 |
|------|---------|------|---------|
| **glm-4-flash** | OpenAI 兼容 `/chat/completions` | 文本对话、Prompt 优化、Query Rewrite | Spring AI ChatClient |
| **glm-4.5v** | OpenAI 兼容 `/chat/completions` | 图片视觉分析（画像生成、RAG 检索 query 提取） | Spring AI ChatClient + Media |
| **embedding-2** | OpenAI 兼容 `/embeddings` | 文本向量化（1024 维），图片 AI 画像索引入库 | Spring AI EmbeddingModel |
| **CogView-4** | 智谱专用 API `/images/generations` | 文生图（仅 prompt 转图，无图生图能力） | 原生 `java.net.http.HttpClient` |

### 2.2 配置方式

```yaml
spring.ai.openai:
  api-key: ${ZHIPU_API_KEY}
  base-url: https://open.bigmodel.cn/api/paas/v4
  chat.options.model: glm-4-flash
  embedding.options.model: embedding-2

zhipu:
  vision.model: glm-4.5v       # 视觉模型独立配置
  image.api-key: ${ZHIPU_API_KEY}
```

### 2.3 自定义 API 集成

智谱的 CogView-4 生图 API 不是 OpenAI 兼容协议，无法通过 Spring AI 调用，因此写了三个专用类：

| 类 | 职责 |
|----|------|
| `ZhipuImageGenerationService` | 调用 `/images/generations`，CogView-4 文生图 |
| `ZhipuVisionAnalysisService` | 调 glm-4.5v 理解图片内容，生成结构化 `VisionAnalysisResult` |
| `CogViewImageApi` | 下载 CogView-4 生成的图片 URL（支持 SSRF 防护、重定向跟随） |

### 2.4 已知限制

- **CogView-4 仅文生图**：API 无 `image`/`reference_image` 参数，无 `/images/edits` 端点。当前 RAG 链路本质是"图片→视觉特征→文字检索→拼 Prompt→文生图"，不是真正的图生图。
- **embedding-2 相似度偏低**：语义相关内容余弦相似度通常 0.4~0.5，`min-score` 设 0.4 较合理。
- **GLM-Image 图生图 API 未开放**：模型层面支持，但 API 未公开。

---

## 3. PostgreSQL + pgvector —— 向量存储

### 3.1 配置

```yaml
spring.ai.vectorstore.pgvector:
  initialize-schema: true
  index-type: ivfflat
  distance-type: cosine_distance
  dimensions: 1024          # embedding-2 输出 1024 维
  table-name: picture_vector_store
```

### 3.2 向量索引抽象

```
VectorIndexService (接口)
  ├── upsert(docId, text)     → embed + INSERT
  ├── delete(docId)           → DELETE
  └── search(query, topK)     → embed → cosine_distance → 召回

PgVectorIndexService (@Profile("!test"))
  └── 基于 Spring AI PgVectorStore
```

### 3.3 索引流程

```
图片入库 → 调 glm-4.5v 分析 → VisionAnalysisResult
  → indexText = "主题:X 场景:Y 风格:Z 色彩:R/G/B 构图:A 光影:B 氛围:C"
  → embedding-2 向量化 → pgvector upsert
  → RAG 检索时: 用户 query → embedding-2 → cosine_distance → top-K
```

---

## 4. Redis —— ChatMemory 会话记忆

### 4.1 存储结构

```
Key:   chat:memory:{conversationId}
Type:  Redis List
TTL:   7 天（可配置 chat-memory.ttl-days）
截断:  LRANGE 取最后 N 条 + LTRIM 限制长度
```

### 4.2 图片记忆分流

聊天历史中的图片引用分两种存储方式：

| 类型 | 存储内容 | 还原方式 |
|------|---------|---------|
| `GALLERY:pictureId` | 图库中的图片 | 从对象存储下载 → 还原为 Media |
| `TEXT_DESCRIPTION` | 外部图片的视觉分析文字描述 | 拼接到上下文中 |

### 4.3 双层存储

```
热数据：Redis（短期记忆，快速读写）
冷数据：PostgreSQL chat_message 表（完整历史，持久化）
```

---

## 5. Resilience4j —— AI 调用容错

### 5.1 熔断器（CircuitBreaker）

三组熔断器，配置相同（滑动窗口 10，失败率 >50% 触发熔断，30s 后半开尝试）：

| 熔断器 | 保护对象 |
|--------|---------|
| `zhipu-vision` | `ZhipuVisionAnalysisService` 视觉分析 |
| `zhipu-image-gen` | `ZhipuImageGenerationService` 图片生成 |
| `zhipu-image-download` | `CogViewImageApi` 图片下载 |

### 5.2 重试（Retry）

| 重试器 | 最大重试 | 间隔 | 重试的异常 |
|--------|---------|------|-----------|
| `zhipu-vision` | 3 次 | 1s | `HttpServerErrorException`、`SocketTimeoutException` |
| `zhipu-image-gen` | 3 次 | 2s | 同上 |

---

## 6. 其他基础设施（支撑 AI 链路）

| 组件 | 作用 | 备注 |
|------|------|------|
| **Project Reactor** (WebFlux) | SSE 流式响应 `Flux<StreamEventVO>` | Spring Boot Web starter 自带 |
| **Flyway** | 数据库版本迁移（V1~V4） | gallery_picture / picture_ai_profile / chat_message / gallery_cache |
| **Hutool** | 通用工具（JSON/文件/加解密） | 5.8.37 |
| **SpringDoc OpenAPI** | API 文档 `/api/doc.html` | 2.8.13 |
| **Lombok** | 减少样板代码 | 1.18.36 |

---

## 面试要点

1. **为什么选 Spring AI 而不是直接调 HTTP API？** → Spring AI 提供了 Advisor 拦截链（关注点分离）、ChatMemory 抽象（多轮对话记忆管理）、结构化输出（BeanOutputConverter）、流式 SSE 支持（Flux 集成），以及多模型供应商的协议适配。直接调 HTTP 需要自己实现所有这些。

2. **为什么用 OpenAI 协议对接智谱？** → 智谱提供了 OpenAI 兼容端点（`open.bigmodel.cn/api/paas/v4`），Spring AI 的 `spring-ai-starter-model-openai` 原生支持。只需改 `base-url` + `api-key` 即可切换模型供应商。零代码切换智谱→DeepSeek→OpenAI。

3. **CogView-4 为什么没用 Spring AI 的 ImageModel？** → Spring AI 的 Image API 也是 OpenAI 协议（`/images/generations`），但 CogView-4 的请求/响应格式与 OpenAI 不完全兼容，且当前 Spring AI ImageModel 生态不如 ChatModel 成熟。实际使用原生 HttpClient 调用再封装，配合 Resilience4j 熔断。

4. **为什么视觉模型和文本模型分开配置？** → glm-4.5v 视觉模型按 token 计费比 glm-4-flash 贵，且调用频率不同（视觉分析只在图片入库时触发，文本对话每次请求都触发）。分开配置便于独立调参熔断/重试策略。
