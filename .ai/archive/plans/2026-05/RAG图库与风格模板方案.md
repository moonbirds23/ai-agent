# RAG 图库与风格模板方案

## 目标

当前 `ai-agent` 需要在不依赖 Picture-Backend 的情况下，先具备可测试的图库与 RAG 能力。方案目标是：

- 支持用户上传或导入图片，形成当前项目内的单用户图库。
- 支持用户从图库中选择多张图片作为显式参考图。
- 支持从用户历史收藏图中做 RAG 检索，辅助生图 Prompt 增强。
- 支持系统内置风格模板兜底，解决冷启动没有知识库的问题。
- 前端提供简易调试界面，能验证参考图、RAG 召回、模板命中和最终 Prompt。
- 字段和接口语义尽量对齐 Picture-Backend，后续并入时将当前图库作为公共图库迁移。

核心优先级：

```text
用户明确上传/选择参考图 > 用户历史收藏图 RAG 检索 > 系统内置风格模板兜底 > 普通 Prompt 优化
```

## 当前边界

本方案只针对当前 `ai-agent` 项目，不直接修改 Picture-Backend。

当前项目先按单用户模式处理：

```text
userId = 1
spaceId = 0
reviewStatus = 1
```

后续并入 Picture-Backend 时：

- `GalleryPicture` 对齐 Picture-Backend 的 `Picture`。
- `PictureAiProfile` 作为 AI/RAG 扩展数据。
- 当前单用户图库可迁移为公共图库或系统图库。

## 总体架构

建议拆成 5 个模块：

```text
gallery/    图库管理：上传、导入、分页、收藏、选择参考图
profile/    图片 AI 画像：视觉分析、索引文本生成
rag/        三层 RAG 策略：显式参考图、收藏图检索、模板兜底
template/   系统风格模板：PPT、海报、插画等场景模板
frontend/   简易测试台：图库、多选参考、RAG 开关、Prompt 调试
```

推荐调用链：

```text
ChatController
  -> PictureApp
    -> RagService.buildContext(request)
      -> ExplicitReferenceResolver
      -> GalleryRagRetriever
      -> StyleTemplateService
    -> PromptReferenceAssembler.assemble(userInput, ragContext)
    -> ChatClient
    -> ImageGenerationService
```

## 数据设计

### GalleryPicture

当前项目内的轻量图库对象，字段尽量贴近 Picture-Backend 的 `Picture`：

```java
public record GalleryPicture(
        Long id,
        String url,
        String thumbnailUrl,
        String name,
        String introduction,
        String category,
        List<String> tags,
        Long picSize,
        Integer picWidth,
        Integer picHeight,
        Double picScale,
        String picFormat,
        Long userId,
        Long spaceId,
        Integer reviewStatus,
        String picColor,
        String sourceType,
        Boolean favorited,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {}
```

`sourceType` 建议值：

```text
upload              用户上传
import_url          URL 导入
generated           AI 生成但未收藏
generated_saved     AI 生成并保存
favorite            用户收藏
reference           用户设为参考
template            系统模板素材
```

### PictureAiProfile

不要把所有 AI 分析字段都塞进 `GalleryPicture`。单独维护 AI 画像，方便后续并入 Picture-Backend：

```java
public record PictureAiProfile(
        Long pictureId,
        String subject,
        String scene,
        String style,
        String colors,
        String composition,
        String lighting,
        String mood,
        String imagePrompt,
        String indexText,
        Integer vectorStatus,
        LocalDateTime analyzedAt
) {}
```

`indexText` 是向量入库文本，推荐格式：

```text
名称: ...
简介: ...
分类: ...
标签: ...
主体: ...
场景: ...
风格: ...
色彩: ...
构图: ...
光影: ...
氛围: ...
可复用 Prompt: ...
```

### 本地存储

当前项目开发阶段先用文件存储：

```text
gallery-data/
  images/
    {pictureId}.{ext}
  pictures.json
  ai-profiles.json
  vector-store.json
```

注意：

- `gallery-data/` 不应提交 Git。
- `pictures.json` 保存图库元数据。
- `ai-profiles.json` 保存视觉分析结果。
- `vector-store.json` 保存开发期 `SimpleVectorStore`。

后续生产化替换：

```text
图片文件：COS / OSS / 本地图库服务
图片元数据：Picture-Backend picture 表
AI 画像：picture_ai_profile 表
向量索引：pgvector / Milvus / Elasticsearch dense vector
```

## 接口设计

### 图库接口

```text
POST   /gallery/upload
POST   /gallery/import-url
GET    /gallery/page
GET    /gallery/{id}
POST   /gallery/{id}/favorite
POST   /gallery/{id}/analyze
DELETE /gallery/{id}
POST   /gallery/reference/resolve
```

### 上传图片

```json
{
  "imageBase64": "data:image/png;base64,...",
  "name": "春日插画",
  "introduction": "柔和治愈风格",
  "category": "illustration",
  "tags": ["春天", "治愈", "插画"],
  "favorited": true
}
```

### URL 导入

```json
{
  "imageUrl": "https://example.com/a.png",
  "name": "商务 PPT 背景",
  "category": "ppt",
  "tags": ["PPT", "商务", "蓝色"]
}
```

### 多图参考解析

```json
{
  "referencePictureIds": [101, 102, 103],
  "referenceMode": "style"
}
```

`referenceMode` 支持：

```text
overall       综合参考
style         参考风格
color         参考色彩
composition   参考构图
```

### ChatRequest 扩展

建议扩展当前 `ChatRequest`：

```java
List<Long> referencePictureIds;
Boolean useGalleryRag;
String referenceMode;
String styleTemplateCode;
Boolean saveGeneratedToGallery;
```

默认行为：

```text
referencePictureIds 非空 -> 启用第一层显式参考图
useGalleryRag null -> 默认 true
styleTemplateCode 非空 -> 使用指定模板
saveGeneratedToGallery true -> 生成成功后保存进图库
```

## 三层 RAG 策略

### 第一层：显式参考图

用户上传或从图库选择的参考图优先级最高。

实现方式：

```text
referencePictureIds
  -> GalleryPictureRepository.findByIds()
  -> PictureAiProfileRepository.findByPictureIds()
  -> ReferencePicture 列表
```

这层不做向量检索。语义是：

```text
只参考风格、色彩、构图、光影和氛围，不复制具体主体。
```

如果图片没有 AI 画像：

- 前端可提示“未分析”。
- 后端可在首次引用时自动调用 `VisionAnalysisService` 补分析。

### 第二层：历史收藏图 RAG 检索

只检索用户认可过的图片：

```text
favorited = true
或 sourceType in (generated_saved, favorite, reference)
```

检索流程：

```text
用户需求
  -> RagQueryBuilder 生成检索 query
  -> EmbeddingModel
  -> VectorStore similaritySearch
  -> userId / favorited / sourceType 过滤
  -> minScore 过滤
  -> topK 截断
  -> ReferencePicture 列表
```

建议配置：

```yaml
app:
  rag:
    enabled: true
    top-k: 5
    min-score: 0.65
    max-context-chars: 2500
    retrieve-favorites-only: true
```

### 第三层：系统内置风格模板兜底

触发条件：

```text
没有显式参考图
且历史收藏图召回为空或分数过低
```

也支持用户手动指定 `styleTemplateCode`。

模板文件建议放在：

```text
src/main/resources/rag/style-templates.yml
```

模板示例：

```yaml
- code: ppt-business-flat
  name: PPT 商务扁平插画
  scene: work_study
  category: ppt
  keywords: [PPT, 商务, 汇报, 数据, 职场, 扁平]
  prompt: >
    扁平矢量插画风格，清晰的信息层级，干净背景，
    低噪声构图，适合 PPT 页面留白，蓝绿点缀色，
    画面主体明确，不要复杂纹理。
  negativePrompt: >
    避免过度写实，避免杂乱背景，避免小字和复杂 UI 截图。
  suggestedDimensions: 16:9

- code: ppt-tech-isometric
  name: PPT 科技等距插画
  scene: work_study
  category: ppt
  keywords: [科技, AI, 系统架构, 云服务, 数据平台]
  prompt: >
    等距 3D 插画，科技蓝与青色点缀，模块化结构，
    适合表达系统、数据流、云平台和 AI 能力。
  suggestedDimensions: 16:9
```

首批模板建议：

```text
PPT 商务扁平插画
PPT 科技等距插画
PPT 数据报告背景
儿童绘本插画
日系清新生活记录
写实产品摄影
国潮节日海报
社交媒体可爱贴纸风
极简线稿图标风
3D 柔和毛玻璃风
```

## Prompt 改造

新增 RAG 专用模板：

```text
src/main/resources/prompts/default/generation_with_rag.st
```

模板结构：

```text
你是云图库 AI 图片生成助手，请基于用户需求生成结构化生图参数。

【用户需求】
{userInput}

【明确参考图，优先级最高】
{explicitReferences}

【历史收藏图参考】
{retrievedReferences}

【系统风格模板】
{styleTemplate}

【生成约束】
1. 用户需求优先。
2. 明确参考图优先级高于历史收藏图。
3. 历史收藏图只用于风格、色彩、构图、光影和氛围参考。
4. 不要复制参考图中的具体主体，除非用户明确要求。
5. 如果参考资料冲突，以用户需求和明确参考图为准。
6. 如果参考资料不足，按用户需求正常生成。
7. 输出必须符合以下 JSON 格式：
{outputFormat}
```

RAG 上下文示例：

```text
参考图 1：
- 名称：春日儿童插画
- 标签：春天、儿童、治愈
- 风格：柔和扁平插画
- 色彩：浅绿、奶油白、低饱和
- 构图：中心主体，背景留白
- 光影：自然柔光
- 氛围：温暖、治愈
```

注意：

- RAG 上下文只作为本次调用临时 Prompt。
- 不要把增强后的完整 Prompt 写入 `ChatMemory`。
- `ChatMemory` 里保存用户原始需求和最终回复。

## 前端简易调试台

当前前端先做测试台，不做完整产品化 UI。

建议三栏：

```text
左侧：图库
中间：对话与生图
右侧：参考配置与调试信息
```

### 左侧图库

功能：

- 图片上传。
- URL 导入。
- 缩略图网格。
- 搜索框。
- 分类和标签筛选。
- 收藏按钮。
- 多选参考图。
- 重新分析按钮。

### 中间对话与生图

功能：

- 模式选择：聊天 / 图片分析 / 图片生成。
- 消息输入。
- 流式输出。
- 生成结果预览。
- 保存到图库。
- 保存并收藏。

### 右侧参考与调试

功能：

- 已选择参考图列表。
- 参考模式选择：综合 / 风格 / 色彩 / 构图。
- 是否启用历史收藏 RAG。
- 系统模板选择。
- 展示本次 RAG 召回图。
- 展示命中的系统模板。
- 展示最终增强 Prompt。

测试时必须能看见：

```text
选了哪些参考图
RAG 召回了哪些收藏图
命中了哪个系统模板
最终增强 Prompt 是什么
生成结果是什么
```

## 步骤分工

### 阶段 1：图库基础能力

目标：当前项目具备单用户图库。

任务：

- 新增 `gallery` 模块。
- 实现 `GalleryPicture` 模型。
- 实现本地图片存储。
- 实现 `pictures.json` 元数据存储。
- 实现上传、URL 导入、分页、详情、删除、收藏接口。
- 前端展示图库缩略图和收藏状态。

验收：

- 可上传图片。
- 可 URL 导入图片。
- 可分页查看图库。
- 可收藏和取消收藏。
- 可删除图片。

### 阶段 2：图片 AI 画像与索引文本

目标：每张图可以生成可检索的视觉语义描述。

任务：

- 新增 `PictureAiProfile`。
- 上传或手动触发时调用 `VisionAnalysisService`。
- 生成 `indexText`。
- 保存到 `ai-profiles.json`。
- 前端展示图片分析结果。

验收：

- 图片详情能看到主体、场景、风格、色彩、构图、光影、氛围。
- 没有 AI 画像的图片能手动重新分析。
- `indexText` 可用于向量入库。

### 阶段 3：向量索引与收藏图检索

目标：收藏图可作为历史知识被 RAG 检索。

任务：

- 接入 `EmbeddingModel` 和 `VectorStore`。
- 收藏图或 AI 画像更新后写入向量库。
- 删除图片时同步删除向量。
- 实现 `GalleryRagRetriever`。
- 增加 `app.rag` 配置项。

验收：

- 输入生图需求能检索出相关收藏图。
- 未收藏图片默认不参与检索。
- 检索结果受 `topK` 和 `minScore` 控制。

### 阶段 4：系统风格模板兜底

目标：冷启动无图库时仍能获得稳定风格增强。

任务：

- 新增 `style-templates.yml`。
- 实现 `StyleTemplateService`。
- 支持关键词匹配模板。
- 支持用户指定 `styleTemplateCode`。
- 前端展示模板列表和当前命中模板。

验收：

- 无收藏图时能命中 PPT / 海报 / 插画等模板。
- 用户可手动选择模板。
- 模板内容能进入最终 Prompt。

### 阶段 5：三层 RAG 编排与 Prompt 改造

目标：显式参考图、收藏图 RAG、模板兜底形成统一上下文。

任务：

- 新增 `RagContext`。
- 新增 `RagService.buildContext()`。
- 新增 `PromptReferenceAssembler`。
- 新增 `generation_with_rag.st`。
- 扩展 `ChatRequest`。
- 改造 `PictureApp` 生图模式调用。

验收：

- 多选参考图时，第一层优先进入 Prompt。
- 没有参考图时，检索收藏图。
- 收藏图无结果时，使用系统模板。
- 最终 Prompt 不写入 `ChatMemory`。

### 阶段 6：前端调试台

目标：能完成端到端场景测试。

任务：

- 左侧图库管理。
- 中间对话和生图。
- 右侧参考配置。
- 展示 RAG 召回结果。
- 展示最终增强 Prompt。

验收：

- 能选多张图库图片作为参考。
- 能开关历史收藏 RAG。
- 能选择模板。
- 能看到最终 Prompt 和生成结果。

## 并行 Agent 分工

### Agent A：图库与本地存储

职责：

- `gallery` 模块。
- `GalleryPicture` 模型。
- 本地文件保存。
- `pictures.json` 读写。
- `/gallery` 基础接口。

禁止修改：

- `PictureApp` 生图主链路。
- `PromptOptimizeAdvisor`。
- RAG Prompt 模板。

交付契约：

```java
interface GalleryService {
    GalleryPicture upload(GalleryUploadRequest request);
    GalleryPicture importUrl(GalleryImportUrlRequest request);
    Page<GalleryPicture> page(GalleryQueryRequest request);
    GalleryPicture getById(Long id);
    List<GalleryPicture> listByIds(List<Long> ids);
    GalleryPicture favorite(Long id, boolean favorited);
    void delete(Long id);
}
```

### Agent B：AI 画像与向量索引

职责：

- `PictureAiProfile`。
- `PictureAiProfileService`。
- 调用 `VisionAnalysisService`。
- 生成 `indexText`。
- 写入和删除向量。

依赖：

- Agent A 的 `GalleryService.getById()`。

禁止修改：

- 前端页面结构。
- ChatController 接口。

交付契约：

```java
interface PictureAiProfileService {
    PictureAiProfile analyze(Long pictureId);
    PictureAiProfile getByPictureId(Long pictureId);
    List<PictureAiProfile> listByPictureIds(List<Long> pictureIds);
    void index(Long pictureId);
    void removeIndex(Long pictureId);
}
```

### Agent C：RAG 编排与 Prompt

职责：

- `RagContext`。
- `RagService`。
- `GalleryRagRetriever`。
- `PromptReferenceAssembler`。
- `generation_with_rag.st`。
- `ChatRequest` 扩展。
- `PictureApp` 生图模式接入。

依赖：

- Agent A 的 `GalleryService`。
- Agent B 的 `PictureAiProfileService`。
- Agent D 的 `StyleTemplateService`。

交付契约：

```java
interface RagService {
    RagContext buildContext(ChatRequest request, String chatId);
}

interface PromptReferenceAssembler {
    String assemble(String userInput, RagContext context, String outputFormat);
}
```

### Agent D：系统风格模板

职责：

- `style-templates.yml`。
- `StyleTemplate` 模型。
- `StyleTemplateService`。
- 关键词匹配。
- 模板查询接口。

禁止修改：

- 图库本地存储。
- 向量检索逻辑。

交付契约：

```java
interface StyleTemplateService {
    List<StyleTemplate> list();
    StyleTemplate getByCode(String code);
    Optional<StyleTemplate> match(String userInput);
}
```

### Agent E：前端调试台

职责：

- `static/index.html`。
- 图库三栏测试界面。
- 上传、导入、收藏、多选参考图。
- 调用聊天和生图接口。
- 展示 RAG 召回结果和最终 Prompt。

依赖：

- Agent A 的 `/gallery` 接口。
- Agent C 的扩展 ChatRequest。
- Agent D 的模板列表接口。

禁止修改：

- 后端业务服务实现。

## 并行开发同步规则

为了避免多个 agent 互相覆盖，必须遵守：

1. 先冻结接口契约，再并行实现。
2. 每个 agent 只修改自己负责的包。
3. 公共 DTO 变更必须先记录在本文件或单独接口文档中。
4. `PictureApp` 只允许 Agent C 修改。
5. `static/index.html` 只允许 Agent E 修改。
6. `application.yml` 配置新增由对应模块 agent 添加，但 key 必须放在 `app.gallery`、`app.rag`、`app.template` 下。
7. 所有新接口返回 `BaseResponse<T>`。
8. 业务校验统一用 `ThrowUtils.throwIf()`。
9. Maven 编译通过后再合并各 agent 结果。

建议合并顺序：

```text
Agent A -> Agent B -> Agent D -> Agent C -> Agent E
```

其中 Agent C 是集成点，必须最后接主链路。

## 测试场景

### 场景 1：无图库冷启动

步骤：

1. 不上传任何图片。
2. 用户输入“生成一张适合 PPT 的 AI 平台架构配图”。
3. 系统命中 `ppt-tech-isometric` 或类似模板。
4. 生成 Prompt 中包含 PPT 风格模板。

验收：

- 没有 RAG 收藏图。
- 有系统模板。
- 生图参数稳定。

### 场景 2：多张显式参考图

步骤：

1. 上传 3 张图片。
2. 对图片执行 AI 分析。
3. 在前端多选为参考图。
4. 输入“按这些图的风格生成一张儿童节海报”。

验收：

- Prompt 中包含 3 张显式参考图摘要。
- 显式参考图优先级高于模板。
- 不依赖历史收藏图检索。

### 场景 3：历史收藏图 RAG

步骤：

1. 上传并收藏多张图片。
2. 输入“生成一张温柔治愈风格的生活记录封面”。
3. 系统检索收藏图。

验收：

- 只召回收藏图。
- 召回结果与“温柔、治愈、生活记录”相关。
- Prompt 中包含历史收藏图参考。

### 场景 4：保存生成结果到图库

步骤：

1. 生图请求中 `saveGeneratedToGallery=true`。
2. 图片生成成功。
3. 系统保存图片到图库。
4. 用户点击收藏。
5. 后续 RAG 可检索该图。

验收：

- 生成图进入图库。
- 收藏后进入向量索引。
- 后续检索可召回。

## 风险与注意事项

- 当前 `SimpleVectorStore` 只适合开发测试，生产需要替换。
- `gallery-data/` 必须加入 `.gitignore`。
- RAG 上下文不要写入 ChatMemory。
- 显式参考图和历史收藏图只参考风格，不默认复制主体。
- 模板兜底不能过强，用户需求优先。
- 上传图片需限制大小、格式和数量。
- 本地文件读取必须防路径穿越。
- 后续并入 Picture-Backend 时要处理真实 userId、spaceId 和权限。

## 最小可行版本

如果时间有限，建议 MVP 只做：

1. 本地图库上传和分页。
2. 图片收藏。
3. 手动触发 AI 分析。
4. 收藏图向量检索。
5. YAML 模板兜底。
6. 生图模式注入三层 RAG Prompt。
7. 前端展示最终 Prompt。

MVP 暂不做：

- 多用户权限。
- 空间协作。
- 复杂分页筛选。
- 生产级向量库。
- 自动批量分析。
- 完整 Picture-Backend 数据迁移。

## 后续并入 Picture-Backend 的迁移点

当前项目对象到 Picture-Backend 的映射：

```text
GalleryPicture.id             -> Picture.id
GalleryPicture.url            -> Picture.url
GalleryPicture.thumbnailUrl   -> Picture.thumbnailUrl
GalleryPicture.name           -> Picture.name
GalleryPicture.introduction   -> Picture.introduction
GalleryPicture.category       -> Picture.category
GalleryPicture.tags           -> Picture.tags
GalleryPicture.picSize        -> Picture.picSize
GalleryPicture.picWidth       -> Picture.picWidth
GalleryPicture.picHeight      -> Picture.picHeight
GalleryPicture.picScale       -> Picture.picScale
GalleryPicture.picFormat      -> Picture.picFormat
GalleryPicture.userId         -> Picture.userId
GalleryPicture.spaceId        -> Picture.spaceId
GalleryPicture.reviewStatus   -> Picture.reviewStatus
GalleryPicture.picColor       -> Picture.picColor
```

新增扩展表建议：

```text
picture_ai_profile
picture_vector_index
style_template
```

并入时优先保留：

- `RagService`
- `GalleryRagRetriever`
- `StyleTemplateService`
- `PromptReferenceAssembler`
- `generation_with_rag.st`

替换：

- `GalleryRepository`
- `PictureAiProfileRepository`
- 本地 `SimpleVectorStore`
- 本地文件存储
