package com.zzp.aiagent.tool;

import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.domain.template.StyleTemplate;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.entity.PictureAiProfile;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.model.vo.ImageGeneratedEventVO;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.HybridGalleryRetriever;
import com.zzp.aiagent.service.ImageGenerationService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.service.VisionAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Agent tools that the LLM can invoke autonomously.
 * <p>
 * Each {@code @Tool} method is discovered by Spring AI's
 * {@code MethodToolCallbackProvider} and registered with the ChatClient.
 * The model decides when to call which tool based on user intent.
 */
@Component
@Profile("!test")
@Slf4j
public class GalleryAgentTools {

    private final GalleryService galleryService;
    private final ImageGenerationService imageGenerationService;
    private final VisionAnalysisService visionAnalysisService;
    private final StyleTemplateService styleTemplateService;
    private final PictureAiProfileService profileService;
    private final HybridGalleryRetriever hybridRetriever;
    private final CurrentImageContext currentImageContext;
    private final ToolProgressContext progressContext;

    public GalleryAgentTools(GalleryService galleryService,
                             ImageGenerationService imageGenerationService,
                             VisionAnalysisService visionAnalysisService,
                             StyleTemplateService styleTemplateService,
                             PictureAiProfileService profileService,
                             HybridGalleryRetriever hybridRetriever,
                             CurrentImageContext currentImageContext,
                             ToolProgressContext progressContext) {
        this.galleryService = galleryService;
        this.imageGenerationService = imageGenerationService;
        this.visionAnalysisService = visionAnalysisService;
        this.styleTemplateService = styleTemplateService;
        this.profileService = profileService;
        this.hybridRetriever = hybridRetriever;
        this.currentImageContext = currentImageContext;
        this.progressContext = progressContext;
    }

    // ── 图库搜索 ────────────────────────────────────────────────────

    @Tool(name = "searchGallery",
          description = """
                  Search the user's image gallery for reference pictures. \
                  Use this when you need to find existing images matching a style, theme, \
                  color, or subject before generating a new image, or when the user asks \
                  to find/show specific pictures. Returns matching pictures with their \
                  IDs, names, and key visual attributes.""")
    public String searchGallery(
            @ToolParam(required = false,
                       description = "Search query describing what to find, e.g. 'winter landscapes', 'minimalist portraits'")
            String query,
            @ToolParam(required = false,
                       description = "Maximum results to return (1-10, default 5)")
            Integer limit,
            ToolContext toolContext) {

        int n = (limit != null && limit > 0 && limit <= 10) ? limit : 5;
        String q = (query != null && !query.isBlank()) ? query.strip() : "";
        progressContext.start(toolContext, "searchGallery", "正在搜索图库：" + (q.isBlank() ? "全部参考图" : q));

        List<GalleryPicture> results;
        if (!q.isEmpty()) {
            // Try hybrid vector+keyword search first
            RagSearchCriteria criteria = new RagSearchCriteria(
                    q, null, null, null, null, null, false, null,
                    n * 2, n, 0.3);
            results = hybridRetriever.retrieve(criteria).stream()
                    .map(RagCandidate::picture)
                    .distinct()
                    .limit(n)
                    .toList();
            if (results.isEmpty()) {
                results = galleryService.searchByKeyword(q, n);
            }
        } else {
            results = List.of();
        }

        if (results.isEmpty()) {
            progressContext.done(toolContext, "searchGallery", "图库中没有找到相关图片");
            return "图库中没有找到与「" + q + "」相关的图片。";
        }

        progressContext.done(toolContext, "searchGallery", "图库搜索完成，找到 " + results.size() + " 张相关图片");
        StringBuilder sb = new StringBuilder("找到 ").append(results.size()).append(" 张相关图片：\n");
        for (int i = 0; i < results.size(); i++) {
            GalleryPicture p = results.get(i);
            sb.append(i + 1).append(". [ID:").append(p.id()).append("] ");
            sb.append(p.name());
            if (p.introduction() != null && !p.introduction().isBlank()) {
                sb.append(" — ").append(p.introduction());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── 图片详情查询 ────────────────────────────────────────────────

    @Tool(name = "getPictureInfo",
          description = """
                  Get detailed information about a specific picture by its ID, \
                  including AI-analyzed style, colors, composition, and mood.""")
    public String getPictureInfo(
            @ToolParam(required = true,
                       description = "The picture ID to look up")
            Long pictureId,
            ToolContext toolContext) {

        progressContext.start(toolContext, "getPictureInfo", "正在读取参考图 ID:" + pictureId + " 的视觉信息");
        GalleryPicture picture = galleryService.getById(pictureId);
        if (picture == null) {
            progressContext.done(toolContext, "getPictureInfo", "未找到参考图 ID:" + pictureId);
            return "未找到 ID 为 " + pictureId + " 的图片。";
        }

        PictureAiProfile profile = null;
        try {
            profile = profileService.getByPictureId(pictureId);
        } catch (Exception ignored) {
            // Profile may not exist yet
        }

        StringBuilder sb = new StringBuilder("图片详情 [ID:").append(picture.id()).append("]\n");
        sb.append("名称：").append(picture.name()).append("\n");
        if (picture.introduction() != null && !picture.introduction().isBlank()) {
            sb.append("简介：").append(picture.introduction()).append("\n");
        }
        sb.append("格式：").append(picture.picFormat()).append("\n");
        sb.append("宽高：").append(picture.picWidth()).append("×").append(picture.picHeight()).append("\n");
        if (picture.tags() != null && !picture.tags().isEmpty()) {
            sb.append("标签：").append(String.join("、", picture.tags())).append("\n");
        }
        sb.append("收藏：").append(picture.favorited() ? "是" : "否").append("\n");

        if (profile != null) {
            sb.append("\n【AI 画像分析】\n");
            appendIfPresent(sb, "主题", profile.subject());
            appendIfPresent(sb, "场景", profile.scene());
            appendIfPresent(sb, "风格", profile.style());
            appendIfPresent(sb, "色彩", profile.colors());
            appendIfPresent(sb, "构图", profile.composition());
            appendIfPresent(sb, "光影", profile.lighting());
            appendIfPresent(sb, "氛围", profile.mood());
        } else {
            sb.append("\n（暂无 AI 画像分析）\n");
        }

        progressContext.done(toolContext, "getPictureInfo", "参考图详情读取完成 ID:" + pictureId);
        return sb.toString();
    }

    // ── 图片分析 ────────────────────────────────────────────────────

    @Tool(name = "analyzeImage",
          description = """
                  Perform detailed visual analysis of the image the user uploaded \
                  in the current turn. Extracts subject, scene, style, colors, \
                  composition, lighting, mood, and a reusable image-generation prompt. \
                  Use this when the user asks to analyze, describe, or evaluate an \
                  image they just uploaded.""")
    public String analyzeImage(
            @ToolParam(required = false,
                       description = "What aspect to focus on, e.g. 'analyze the color palette', 'focus on the composition style'")
            String instruction,
            ToolContext toolContext) {

        progressContext.start(toolContext, "analyzeImage", "正在分析当前图片");
        String imageBase64 = currentImageContext.getImageBase64(toolContext);
        if (imageBase64 == null || imageBase64.isBlank()) {
            progressContext.done(toolContext, "analyzeImage", "当前对话中没有可分析的图片");
            return "当前对话中没有可分析的图片。请先上传一张图片。";
        }

        String prompt = (instruction != null && !instruction.isBlank())
                ? instruction
                : "请详细分析这张图片的视觉特征，包括主体、场景、风格、色彩、构图、光影和氛围";

        try {
            VisionAnalysisResult result = visionAnalysisService.analyze(prompt, imageBase64, null);

            StringBuilder sb = new StringBuilder("图片分析结果：\n");
            appendIfPresent(sb, "主体", result.subject());
            appendIfPresent(sb, "场景", result.scene());
            appendIfPresent(sb, "风格", result.style());
            appendIfPresent(sb, "色彩", result.colors());
            appendIfPresent(sb, "构图", result.composition());
            appendIfPresent(sb, "光影", result.lighting());
            appendIfPresent(sb, "氛围", result.mood());
            if (result.imagePrompt() != null && !result.imagePrompt().isBlank()) {
                sb.append("生图 Prompt：").append(result.imagePrompt()).append("\n");
            }
            progressContext.done(toolContext, "analyzeImage", "图片分析完成");
            return sb.toString();

        } catch (Exception e) {
            log.error("[GalleryAgentTools] 图片分析失败", e);
            progressContext.fail(toolContext, "analyzeImage", e.getMessage());
            return "图片分析失败：" + e.getMessage();
        }
    }

    // ── 图片生成 ────────────────────────────────────────────────────

    @Tool(name = "generateImage",
          description = """
                  Generate an AI image based on a prompt description. \
                  Use this when the user explicitly asks to create, generate, draw, or produce \
                  an image. The image is displayed by the system through a structured \
                  image_generated event and is NOT automatically saved to gallery — \
                  the user must explicitly ask to save or favorite it. \
                  Never say an image is generated unless this tool was actually called successfully. \
                  Do not invent, repeat, or rewrite placeholder image URLs in the final answer. \
                  IMPORTANT: the prompt must be in English and detailed enough for \
                  high-quality generation. Include subject, style reference, \
                  color palette, composition notes, lighting, and mood.""")
    public String generateImage(
            @ToolParam(required = true,
                       description = "Detailed English prompt describing the image to generate. Must include subject, style, colors, composition, lighting, and mood.")
            String prompt,
            @ToolParam(required = false,
                       description = "Art style, e.g. '写实', '插画', '二次元', '水墨', '油画', '赛博朋克'")
            String style,
            @ToolParam(required = false,
                       description = "Image dimensions: 1024x1024 (square), 1344x768 (landscape), 768x1344 (portrait), or ratio words 'square'/'landscape'/'portrait'")
            String dimensions,
            ToolContext toolContext) {

        progressContext.start(toolContext, "generateImage", "正在生成图片" + formatGenerationMeta(style, dimensions));
        try {
            ImageGenerationResult result = imageGenerationService.generate(prompt, style, dimensions);

            boolean autoSave = readSaveGeneratedToGallery(toolContext);
            if (autoSave) {
                saveToGallery(prompt, style, result);
                log.info("[AgentTools] 图片生成成功，已自动入库");
            } else {
                log.info("[AgentTools] 图片生成成功 (未入库，等待用户确认)");
            }

            progressContext.imageGenerated(toolContext, new ImageGeneratedEventVO(
                    result.imageUrl(),
                    result.imageBase64(),
                    prompt,
                    result.revisedPrompt(),
                    style,
                    dimensions,
                    result.metadata()
            ));
            progressContext.done(toolContext, "generateImage", "图片生成完成");
            return autoSave
                    ? "图片已生成并自动保存到图库。可以在左侧图库面板查看。"
                    : "图片已生成，并已在界面中展示。图片尚未自动入库；如用户需要保存，可提示点击保存到图库按钮。";
        } catch (Exception e) {
            log.error("[GalleryAgentTools] 图片生成失败", e);
            progressContext.fail(toolContext, "generateImage", e.getMessage());
            return "图片生成失败：" + e.getMessage();
        }
    }

    // ── 风格模板 ────────────────────────────────────────────────────

    @Tool(name = "listStyleTemplates",
          description = """
                  List all available art style templates that can be used for image \
                  generation, including their names, codes, and suggested dimensions. \
                  Use this when the user asks what styles are available or wants to \
                  browse style options.""")
    public String listStyleTemplates(ToolContext toolContext) {
        progressContext.start(toolContext, "listStyleTemplates", "正在查询风格模板");
        List<StyleTemplate> templates = styleTemplateService.listAll();
        if (templates.isEmpty()) {
            progressContext.done(toolContext, "listStyleTemplates", "当前没有可用的风格模板");
            return "当前没有可用的风格模板。";
        }
        progressContext.done(toolContext, "listStyleTemplates", "已查询到 " + templates.size() + " 个风格模板");
        StringBuilder sb = new StringBuilder("可用风格模板（共 ").append(templates.size()).append(" 个）：\n");
        for (StyleTemplate t : templates) {
            sb.append("- ").append(t.code()).append("：").append(t.name());
            if (t.scene() != null && !t.scene().isBlank()) {
                sb.append("（").append(t.scene()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── 收藏管理 ────────────────────────────────────────────────────

    @Tool(name = "manageFavorite",
          description = "Toggle the favorite status of a picture in the gallery.")
    public String manageFavorite(
            @ToolParam(required = true,
                       description = "Picture ID to toggle favorite for")
            Long pictureId,
            @ToolParam(required = true,
                       description = "true to favorite, false to unfavorite")
            Boolean favorited,
            ToolContext toolContext) {

        progressContext.start(toolContext, "manageFavorite", "正在更新图片收藏状态 ID:" + pictureId);
        GalleryPicture result = galleryService.favorite(pictureId, favorited);
        progressContext.done(toolContext, "manageFavorite", favorited ? "已加入收藏" : "已取消收藏");
        return favorited
                ? "已将图片「" + result.name() + "」加入收藏。"
                : "已取消收藏图片「" + result.name() + "」。";
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static boolean readSaveGeneratedToGallery(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return false;
        }
        Object flag = toolContext.getContext().get("saveGeneratedToGallery");
        return flag instanceof Boolean b && b;
    }

    private void saveToGallery(String prompt, String style, ImageGenerationResult result) {
        try {
            String name = prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt;
            List<String> tags = style != null && !style.isBlank()
                    ? List.of(style) : List.of();
            if (result.imageBase64() != null && !result.imageBase64().isBlank()) {
                GalleryUploadRequest req = new GalleryUploadRequest(
                        result.imageBase64(), name, result.revisedPrompt(),
                        "ai-generated", tags, null, StorageLocation.MAIN);
                GalleryPicture saved = galleryService.upload(req);
                log.info("[AgentTools] 生成图片已保存 pictureId={}", saved.id());
            } else if (result.imageUrl() != null && !result.imageUrl().isBlank()) {
                GalleryImportUrlRequest req = new GalleryImportUrlRequest(
                        result.imageUrl(), name, result.revisedPrompt(),
                        "ai-generated", tags);
                GalleryPicture saved = galleryService.importUrl(req);
                log.info("[AgentTools] 生成图片已通过URL导入 pictureId={}", saved.id());
            }
        } catch (Exception e) {
            log.warn("[AgentTools] 保存生成图片失败", e);
        }
    }

    private static String formatGenerationMeta(String style, String dimensions) {
        StringBuilder sb = new StringBuilder();
        if (style != null && !style.isBlank()) {
            sb.append("，风格=").append(style);
        }
        if (dimensions != null && !dimensions.isBlank()) {
            sb.append("，尺寸=").append(dimensions);
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("  ").append(label).append("：").append(value).append("\n");
        }
    }
}
