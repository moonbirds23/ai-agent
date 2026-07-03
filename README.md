# 云图库 AI Agent

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5.14-brightgreen?logo=springboot" alt="Spring Boot 3.5">
  <img src="https://img.shields.io/badge/Spring%20AI-1.0.0-blue?logo=spring" alt="Spring AI 1.0">
  <img src="https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql" alt="PG 16">
  <img src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis" alt="Redis 7">
  <img src="https://img.shields.io/badge/tests-189%20passed-success" alt="189 tests">
  <a href="https://github.com/moonbirds23/ai-agent/actions"><img src="https://github.com/moonbirds23/ai-agent/actions/workflows/build.yml/badge.svg" alt="Build"></a>
</p>

**基于 Spring AI 的多模态 AI Agent，实现对话式图片生成、图库管理与检索增强。** 全链路接入智谱 GLM 大模型（GLM-4-Flash / GLM-4.5V / CogView-4 / Embedding-2），支持流式 SSE 响应、LLM 驱动的任务规划与 WorkflowEngine 工作流引擎、三层 RAG 检索增强、PGVector 语义检索。

> 🎯 项目定位：AI Agent 架构学习与面试展示项目，重点展示 Agent 设计模式、RAG 工程实践和 LLM 应用架构能力。

---

## 架构全景

![系统架构图](docs/plots/系统架构图.png)

> 四层架构：浏览器调试台 → Spring Boot 应用层 → 数据/缓存中间件 → 外部 AI 服务

### Agent 请求处理全流程

![Agent请求处理](docs/plots/Agent请求处理.png)

> 一个用户请求经过的 5 个阶段：预处理 → WorkflowEngine 规划 → 执行器路由 → 步骤执行 → 验收与响应

### WorkflowEngine 意图路由

![意图路由决策树](docs/plots/意图路由决策树.png)

> 11 种意图分类 + 执行器选择策略：规则引擎优先，LLM 规划兜底

### RAG 检索增强链路

![三层RAG索引增强链路](docs/plots/三层RAG索引增强链路.png)

> 三层渐进式检索：显式参考图（优先）→ 混合检索增强（自动）→ 风格模板（兜底）

---

## 功能矩阵

### Agent 任务引擎

| 能力 | 实现 | 代码位置 |
|------|------|----------|
| **意图分类** | 11 种 TaskType 规则引擎 + LLM 规划兜底 | `TaskPlanner.java` → `LlmTaskPlanner.java` |
| **执行器路由** | Hybrid 模式：Auto（Spring AI Tool Calling）/ Manual（步骤顺序执行） | `AgentExecutorRouter.java` |
| **步骤依赖执行** | ManualReactExecutor 按 dependsOn 顺序执行，失败自动阻断 | `ManualReactExecutor.java` |
| **任务验收** | TaskVerifier 按计划逐步骤验收 + RecoveryPolicy 恢复建议 | `TaskVerifier.java` · `RecoveryPolicy.java` |
| **WorkflowEngine** | 统一编排 Plan → Route → Execute → Verify | `WorkflowEngine.java` |

### RAG 检索增强（三层）

| 层次 | 机制 | 技术实现 |
|------|------|----------|
| **Layer 1** | 用户显式选择参考图（≤3 张） | `ExplicitReferenceResolver` — 图库元数据 + AI 画像 |
| **Layer 2** | 自动混合检索 | Query 改写 → PGVector 语义检索 + 关键词匹配 + 元数据加权 → 重排序 → 上下文压缩 |
| **Layer 3** | 风格模板兜底 | 10 套预设模板关键词自动匹配 |

### 对话记忆管理

```
会话请求 ──► Redis (热数据，TTL 7天) ──► PostgreSQL (冷持久化)
                  │                              │
                  │ LRANGE 负索引截断              │ Flyway V3 chat_message 表
                  │ 最多 200 条/会话               │ conversation_id 索引
                  ▼                              ▼
              快速对话恢复                     完整历史审计
```

### 图库管理

- **存储**：PostgreSQL（元数据） + 本地文件系统 / COS（图片文件）
- **向量**：PGVector，Embedding-2 模型，1024 维，IVFFlat 索引
- **AI 画像**：GLM-4.5V 视觉模型分析 → `PictureAiProfile`（主题/风格/色彩/构图/光影/氛围）
- **去重**：SHA-256 哈希（上传/导入前自动检查）
- **缓存图库**：对话图片自动以 CACHE 位置入库，定时清理

### 流式 SSE 事件

| 事件类型 | 说明 |
|----------|------|
| `chatId` | 首条事件，携带会话 ID |
| `task_planned` | TaskPlan 创建，包含步骤列表和执行器选择 |
| `token` | 逐字推送模型输出 |
| `tool_call` | 工具调用名称 + 参数摘要 |
| `task_step_started/completed/failed` | 步骤级生命周期 |
| `progress` | 进度信息 |
| `image_generated` | 生图结果 + 下载/保存按钮 |
| `task_verified` | 验收结果 + Recovery 建议 |
| `done` | 最终结构化 ChatResponseVO |
| `error` | 错误信息（区分 BusinessException 和未知异常） |

### 安全

- **SSRF 防护**：`UrlSecurityValidator` 校验内网地址
- **内容安全**：`ContentGuardAdvisor` 关键词 + 格式校验
- **API Key 认证**：`ApiKeyAuthFilter` + Spring Security（`@Profile("!test")`）
- **速率限制**：`RateLimitInterceptor` — chat 10/min, upload 10/min, 其他 30/min
- **XSS 防护**：前端 `escHtml()` / `escAttr()` / `jsString()` 三层转义

### 生产就绪

- **健康探针**：Spring Boot Actuator + liveness/readiness probes
- **优雅关闭**：`server.shutdown: graceful` + 30s 等待 SSE 完成
- **日志**：logback 控制台 + 文件滚动（30 天/3GB）
- **CI**：GitHub Actions `mvn test` on push/PR
- **容器化**：Dockerfile + docker-compose（PG + Redis + App 编排）
- **熔断降级**：Resilience4j CircuitBreaker + Retry（智谱 3 实例）

---

## 快速启动

### 前置条件

- Docker Desktop 或 Docker Engine
- 智谱 API Key（[申请地址](https://open.bigmodel.cn/)）

### 一键启动

```bash
git clone https://github.com/moonbirds23/ai-agent.git
cd ai-agent

# 设置 API Key
export ZHIPU_API_KEY=你的智谱API密钥
export APP_API_KEY=自定义访问密码

# 启动 PostgreSQL + Redis + App
docker compose up -d

# 确认启动成功
curl http://localhost:8231/api/health
# → {"code":0,"data":"ok","message":"ok"}
```

打开浏览器访问：
- **AI 助手对话台**：`http://localhost:8231`
- **图库管理台**：`http://localhost:8231/gallery.html`
- **Swagger API 文档**：`http://localhost:8231/api/doc.html`

### 本地开发

```bash
# Windows（系统 JDK 1.8 需指定 JAVA_HOME）
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn spring-boot:run -Dspring-boot.run.profiles=local

# 运行测试
JAVA_HOME="D:/develop/java/JDK/jdk-21" mvn test
```

---

## 技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| **语言** | Java | 21 (--enable-preview) | 核心开发语言 |
| **框架** | Spring Boot | 3.5.14 | 应用框架 |
| **AI** | Spring AI | 1.0.0 GA | ChatClient · Advisor · Tool Calling · VectorStore |
| **大模型** | 智谱 GLM | GLM-4-Flash / 4.5V / CogView-4 / Embedding-2 | 文本对话 · 视觉分析 · 图片生成 · 向量嵌入 |
| **数据库** | PostgreSQL 16 | pgvector 扩展 | 元数据存储 + 向量检索 |
| **缓存** | Redis 7 | Lettuce + Commons Pool2 | 会话记忆 · 限流 · 缓存 |
| **迁移** | Flyway | — | 数据库版本管理（7 个迁移脚本） |
| **容错** | Resilience4j | 2.3.0 | 熔断 + 重试（智谱 API） |
| **文档** | SpringDoc OpenAPI | 2.8.13 | API 文档 / Swagger UI |
| **容器** | Docker + Compose | — | 容器化部署编排 |
| **CI** | GitHub Actions | — | 自动化测试 |
| **工具库** | Lombok · Hutool · Jsoup | — | 开发效率 |

---

## 项目结构

```
src/main/java/com/zzp/aiagent
├── agent/                         # Agent 核心
│   ├── Agent.java                 # Agent 封装（ChatClient + Advisor 链）
│   ├── WorkflowEngine.java       # 工作流引擎：Plan → Route → Execute
│   ├── executor/
│   │   ├── AgentExecutor.java     # 执行器接口
│   │   ├── AgentExecutorRouter    # Hybrid 路由选择
│   │   ├── SpringAiAutoToolExecutor  # Spring AI 自动 Tool Calling
│   │   └── ManualReactExecutor   # 步骤依赖顺序执行
│   ├── task/
│   │   ├── TaskType.java          # 11 种意图枚举
│   │   ├── TaskPlanner.java       # 规则引擎规划
│   │   ├── LlmTaskPlanner.java    # LLM 增强规划 + Validate → Repair
│   │   ├── TaskPlan.java          # 计划 record
│   │   ├── TaskStep.java          # 步骤 record（含 dependsOn）
│   │   ├── TaskLedger.java        # 生命周期追踪
│   │   ├── TaskVerifier.java      # 按计划验收
│   │   └── RecoveryPolicy.java    # 恢复建议
│   └── prompt/                    # Prompt 构建与预算管理
├── advisor/                       # Advisor 拦截链（5个）
├── app/                           # 应用编排层（PictureApp 等）
├── config/                        # 配置（Security · RateLimit · Async · Jackson）
├── controller/                    # REST 接口
├── common/                        # 基础设施（BaseResponse · ResultUtils · ThrowUtils）
├── exception/                     # 异常体系（ErrorCode · BusinessException · GlobalExceptionHandler）
├── gallery/                       # 图库管理
├── memory/                        # 记忆治理（分类 · 净化 · 写入 · 上下文构建）
├── model/                         # DTO/VO
├── profile/                       # 图片 AI 画像
├── rag/                           # RAG 检索增强
│   └── enhanc/                    # 增强链路（QueryRewrite · HybridRetrieve · Rerank · ContextPack）
├── service/                       # 服务层
├── storage/                       # 对象存储抽象（Local/COS）
├── template/                      # 风格模板
├── tool/                          # 工具集（GalleryAgent · PexelsSearch · ToolProgress）
└── vector/                        # 向量索引抽象（PgVectorIndexService）
```

---

## 核心设计决策

### 为什么 LLM 规划 + 后端验收？

Spring AI 的 Tool Calling 在复杂多步骤任务中依赖模型判断是否调用工具。当任务有严格顺序依赖时（如"先搜图库→再基于参考图生图"），模型可能跳过搜索直接生成。

本项目的解法：**LLM 负责理解与生成，后端负责规划、执行与验收。**
- `TaskPlanner` 提前制定确定性执行计划
- `ManualReactExecutor` 按 dependsOn 排序步骤，强制按序执行
- `TaskVerifier` 按计划逐步骤检查执行证据，不依赖模型声称的结果

### 为什么不直接用 Spring AI 的自动 Tool Calling？

自动模式用于聊天、简单查询等不需要严格执行顺序的场景。Hybrid 模式（默认）下，`AgentExecutorRouter` 根据 `TaskType` 和步骤依赖自动选择：

| 场景 | 执行器 | 原因 |
|------|--------|------|
| 聊天 / 简单查询 | SpringAiAutoExecutor | 让模型自由选择工具 |
| 图库管理操作 | SpringAiAutoExecutor | 单步操作，无需顺序控制 |
| CREATIVE_WORKFLOW | ManualReactExecutor | 搜图→分析→生图，依赖链 |
| 步骤含 dependsOn | ManualReactExecutor | 步骤间有因果依赖 |

### 为什么 Redis + PostgreSQL 双层记忆？

- **Redis**：毫秒级读取，支持 TTL 自动过期，适合热对话数据
- **PostgreSQL**：永久持久化，支持 SQL 查询，适合审计和统计
- **写入策略**：实时写 Redis（异步写 PG），读取优先 Redis，会话过期从 PG 恢复

---

## 待做 (Roadmap)

- [ ] 智谱 GLM-Image 图生图 API 接入（当前 CogView-4 仅文生图）
- [ ] 图库元数据来源字段补充（图片出处/来源类型追溯）
- [ ] 手动 ReAct 循环模式（参考 yu-ai-agent-master 项目）
- [ ] MCP Server 化工具集封装

---

## License

MIT
