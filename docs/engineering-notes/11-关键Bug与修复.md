# 11 — 关键 Bug 与修复记录

> 这些是踩过的坑，记录下来防止再犯。

## Bug 1：流式 ExceptionGuard 兜底不生效

**现象**：敏感词等 `BusinessException` 在流式路径穿透 ExceptionGuard，直达 PictureApp。

**根因**：Spring AI M6 `DefaultAroundAdvisorChain` 的 Micrometer Observation scope 阻断 `.onErrorResume()` 信号。

**当前缓解**：`PictureApp.doChatStream()` 的 `.onErrorResume()` 手动区分：
- `BusinessException` → 透传具体消息
- 未知异常 → 泛化提示

**状态**：部分缓解，Advisor 层面的流式异常处理仍不可靠。

---

## Bug 2：关键词过滤仅有字面匹配

**现象**："继续我上一个任务"可绕过黑名单，模型仍生成违规内容。

**根因**：`ContentGuardAdvisor` 用 `List.of("暴力","色情","政治敏感")` 做字符串匹配，无语义理解。

**状态**：待修。后续需接 DeepSeek 或独立安全模型做语义审核。

---

## Bug 3：PromptOptimizeAdvisor 无输入时模型幻觉

**现象**：用户发"继续上一个任务"、空描述等无头指令时，LLM 随机猜测主题。

**根因**：模型在缺少上下文时自行推测，非记忆污染。

**状态**：待修。后续可在 PromptOptimize 中检测有效需求描述，无效时跳过优化。

---

## Bug 4：多模态记忆序列化丢失图片引用（已修复）

**现象**：`RedisChatMemory` 只有 `instanceof String` 分支提取 imageRefs，但 `Media.getData()` 实际类型是 `URI`（URL传图）或 `ByteArrayResource`（base64传图），两者都不匹配 → imageRefs 恒为 null。

**修复**：改为 `resolveImageRef(URI)` 匹配图库路径 → `GALLERY:pictureId`；外部图片调视觉分析 → `TEXT_DESCRIPTION`。

---

## Bug 5：`chat_memory_retrieve_size` 不生效（已修复）

**现象**：MCMA 每次从 `ChatMemory.get()` 取全部消息注入 Prompt，消息越多 token 消耗越大。

**根因**：Spring AI 1.0.0 GA 的 `MessageChatMemoryAdvisor` 移除了 `chatMemoryRetrieveSize` 参数。

**修复**：`RedisChatMemory.get()` 内部改用 `LRANGE key (len-N) (len-1)` 只取最后 N 条，从 Redis 网络层截断。

---

## Bug 6：ImageIO 读不了 webp

**现象**：`GalleryServiceImpl.upload()` 中的宽高检测对 webp 图片返回 0x0。

**根因**：`javax.imageio.ImageIO` 不支持 webp 格式。

**状态**：待修。需要引入 webp 解析库或跳过 webp 的尺寸检测。

---

## Bug 7：`@SpringBootApplication(exclude)` 不能被 `@ImportAutoConfiguration` 覆盖

**现象**：主类上 exclude DataSource 后，即使另外的 `@Configuration` 用 `@ImportAutoConfiguration` 重新导入也无效。

**根因**：`@SpringBootApplication(exclude)` 将类加入全局排除列表，不可逆。

**修复**：排除 DataSource 改用 profile 条件（`application-test.yml` 的 `spring.autoconfigure.exclude`），不在主类上 exclude。

---

## Bug 8：智谱 embedding-2 相似度偏低

**现象**：语义相关内容余弦相似度通常只有 0.4~0.5。

**修复**：`min-score` 从 0.65 下调到 0.4，避免大量漏召回。

---

## Bug 9：用户消息模板包含 JSON Schema 导致 StringTemplate4 崩溃

**现象**：`{outputFormat}` 在 user message 中被替换为 JSON Schema 后，StringTemplate4 把 Schema 中的 `{}` 当模板语法解析。

**修复**：输出格式约束只能放在 system prompt 中，不在 user message 中替换。

---

## Bug 10：`LocalObjectStorageService.getUrl()` 返回相对路径

**现象**：`getUrl()` 返回 `/api/gallery/files/{id}` 相对路径，`RedisChatMemory.toMessage()` 中 `new URL(relativePath)` 抛 MalformedURLException。

**状态**：已通过 ImageRef 分类型存储（GALLERY/TEXT_DESCRIPTION）修复了 ChatMemory 侧的问题。`getUrl()` 本身仍返回相对路径，需补全 baseUrl。

---

## Bug 11：`BaseResponse` 反序列化必须保留 `@NoArgsConstructor`

**原因**：Jackson 在反序列化时需要无参构造器。Lombok 的 `@Data` 不会自动生成（因为其他构造器存在），必须显式添加。

---

## Bug 12：`AdvisedRequest.adviseContext(null)` 抛异常

**原因**：Builder 不接受 null，必须显式传 `new HashMap<>()`。

---

## Bug 13：API Key 泄漏风险

**现象**：`application-local.yml` 包含硬编码的 Zhipu API Key，已提交到 Git。

**风险**：任何能访问仓库的人都能看到 API Key。

**建议**：使用环境变量 `ZHIPU_API_KEY`，`application-local.yml` 加入 `.gitignore`。

---

## Bug 14：`onErrorResume` 顺序错误导致异常被吞

**原因**：流式异常处理必须 `.onErrorResume(BusinessException.class, ...)` 在前、`.onErrorResume(e -> ...)` 在后。反之则 `BusinessException` 被父类 `Exception` 捕获。

---

## Bug 15：GalleryPicture 24 字段 record 修改成本高

**影响**：加一个字段需要修改 N 处 `new GalleryPicture(...)` 构造点（Service/Repository/测试）。

**缓解**：record 使用 compact constructor 设置默认值：
```java
public GalleryPicture {
    if (storageLocation == null) storageLocation = StorageLocation.MAIN;
}
```
