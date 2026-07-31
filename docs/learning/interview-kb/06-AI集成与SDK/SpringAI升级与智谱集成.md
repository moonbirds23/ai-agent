# Spring AI 1.0.0 GA 升级与智谱全链路集成

## Spring AI 1.0.0 GA 升级历程

从 M6（Milestone 6）升级到 1.0.0 GA（2025-05-20 发布），经历了全量 API 破坏性迁移。

### 主要 API 重命名

| M6 API | 1.0.0 GA API |
|--------|-------------|
| `CallAroundAdvisor` | `CallAdvisor` |
| `StreamAroundAdvisor` | `StreamAdvisor` |
| `AdvisedRequest` | `ChatClientRequest` |
| `AdvisedResponse` | `ChatClientResponse` |
| `chain.nextAroundCall(request)` | `chain.nextCall(request)` |
| `InMemoryChatMemory` | `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` |
| `MessageChatMemoryAdvisor` 构造器 | Builder 模式 |
| `Media` 包 | `org.springframework.ai.content` |

### ChatMemory 接口变化

GA 版本 `ChatMemory` 新增抽象方法 `get(String conversationId)`：

```java
public interface ChatMemory {
    void add(String conversationId, List<Message> messages);
    List<Message> get(String conversationId, int lastN);  // ← 旧有
    List<Message> get(String conversationId);              // ← GA 新增
    void clear(String conversationId);
}
```

### Advisor Chain 变化

`CallAdvisorChain` / `StreamAdvisorChain` 不再是函数式接口：

```java
// M6: 可以 lambda
CallAroundAdvisorChain chain = request -> response;

// GA: 必须 mock
CallAdvisorChain chain = mock(CallAdvisorChain.class);
```

`ChatClientMessageAggregator`（`spring-ai-client-chat`）替代 `MessageAggregator`（`spring-ai-model`）。

### chat_memory_retrieve_size 被移除

GA 的 `MessageChatMemoryAdvisor` 没有 `chatMemoryRetrieveSize()` 配置项。修复方案：在 `RedisChatMemory.get()` 内部用 Redis `LRANGE` 负索引截断。

## 智谱全链路集成

### 为什么选智谱

- 统一 API Key 管理，一个 key 覆盖文本/视觉/嵌入/生图
- 统一 base-url: `https://open.bigmodel.cn/api/paas/v4`
- 中文语义效果优于 OpenAI 同类模型
- CogView 是目前国内最好的中文生图模型之一

### 接入方式

通过 Spring AI 的 OpenAI 协议适配（`spring-ai-starter-model-openai`），修改 base-url 指向智谱：

```yaml
spring:
  ai:
    openai:
      api-key: ${ZHIPU_API_KEY}
      base-url: https://open.bigmodel.cn/api/paas/v4
      chat:
        completions-path: /chat/completions
        options:
          model: glm-4-flash
      embedding:
        enabled: true
        embeddings-path: /embeddings
        options:
          model: embedding-2
```

视觉模型和生图模型配置独立：

```yaml
zhipu:
  api-key: ${ZHIPU_API_KEY:}
  vision:
    api-key: ${ZHIPU_API_KEY:}
    model: ${ZHIPU_VISION_MODEL:glm-4.5v}
  image:
    api-key: ${ZHIPU_API_KEY:}
```

### 各模型调用方式

| 模型 | Spring AI 集成 | 调用类 |
|------|---------------|--------|
| glm-4-flash（文本） | ChatClient (OpenAI 协议) | 直接注入 ChatModel |
| glm-4.5v（视觉） | ChatClient (Multimodality) | ZhipuVisionAnalysisService |
| embedding-2（嵌入） | EmbeddingClient (VectorStore 内置) | PgVectorIndexService |
| CogView-4（生图） | REST API (RestTemplate) | ZhipuImageGenerationService |

**CogView 为什么不用 Spring AI 的 ImageClient？** Spring AI 1.0.0 的 ImageClient 接口不成熟，且智谱 CogView API 与 OpenAI DALL·E API 协议差异大。直接用 RestTemplate + Jackson 手动解析更可控。

### Resilience4j 熔断与重试 (Phase 3)

为所有外部 API 调用增加弹性能力：

| 实例 | 类型 | 配置 |
|------|------|------|
| `zhipu-vision` | CircuitBreaker + Retry | 滑动窗口10，故障率50%，3次重试(1s间隔) |
| `zhipu-image-gen` | CircuitBreaker | 滑动窗口10，故障率50%，30s半开 |
| `zhipu-image-download` | CircuitBreaker | 滑动窗口10，故障率50% |

注解方式：`@CircuitBreaker(name = "zhipu-vision")` + `@Retry(name = "zhipu-vision")`

### Query Rewrite 超时 (Phase 3)

`RagQueryRewriteServiceImpl` ChatClient 调用增加 30s 超时保护，超时后 fallback 到原始消息。

### HTTP 错误码分类 (Phase 2)

| HTTP 状态码 | 映射 ErrorCode |
|------------|---------------|
| 429 | `AI_RATE_LIMIT` |
| 503 | `AI_MODEL_UNAVAILABLE` |
| 4xx | `PARAMS_ERROR` |
| 5xx | `IMAGE_ANALYSIS_FAILED` / `IMAGE_GENERATION_FAILED` |

### 提示注入防护 (Phase 2)

Query Rewrite 的用户输入用 `<user_query>` XML 标签包裹，System Prompt 增加"只处理标签内内容"的指令边界声明。

## 结构化输出

```java
BeanOutputConverter<ImageAgentResponse> outputConverter =
    new BeanOutputConverter<>(ImageAgentResponse.class);

// system prompt 中注入 JSON Schema
String systemPrompt = promptTemplate.render("system", "outputFormat", outputConverter.getFormat());

// 调用时自动解析
ImageAgentResponse aiResp = chatClient.prompt()...call().entity(outputConverter);
```

**注意**：JSON Schema 只能放 system prompt，不能放 user message。因为 Spring AI 的 StringTemplate4 会将 user message 中的 `{}` 当模板语法解析，与 JSON Schema 的 `{` 冲突导致崩溃。
