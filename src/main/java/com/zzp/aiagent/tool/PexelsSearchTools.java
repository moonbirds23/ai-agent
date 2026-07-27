package com.zzp.aiagent.tool;

import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import com.zzp.aiagent.integration.mcp.ImageRetrievalGateway;
import com.zzp.aiagent.integration.mcp.McpIntegrationProperties;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.model.vo.ImageCandidateVO;
import com.zzp.aiagent.model.vo.ImageCandidatesEventVO;
import com.zzp.aiagent.service.GalleryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Pexels stock photo search tools for AI Agent.
 * <p>
 * Provides high-quality, licensed stock photos as reference material for
 * AI image generation. Complements {@link WebSearchTools} (Bing) with
 * structured metadata (avg_color, alt text, photographer attribution)
 * that enriches the RAG pipeline and prompt construction.
 */
@Component
@Profile("!test")
@Slf4j
public class PexelsSearchTools {

    private static final String SOURCE_PEXELS = "pexels";

    private final PexelsPhotoService pexelsPhotoService;
    private final GalleryService galleryService;
    private final ToolProgressContext progressContext;
    private final TaskLedger taskLedger;
    private final ImageRetrievalGateway imageRetrievalGateway;
    private final McpIntegrationProperties mcpProperties;

    public PexelsSearchTools(PexelsPhotoService pexelsPhotoService,
                             GalleryService galleryService,
                             ToolProgressContext progressContext,
                             TaskLedger taskLedger,
                             ImageRetrievalGateway imageRetrievalGateway,
                             McpIntegrationProperties mcpProperties) {
        this.pexelsPhotoService = pexelsPhotoService;
        this.galleryService = galleryService;
        this.progressContext = progressContext;
        this.taskLedger = taskLedger;
        this.imageRetrievalGateway = imageRetrievalGateway;
        this.mcpProperties = mcpProperties;
    }

    // ── 搜索参考图（不入库）───────────────────────────────────────────

    @Tool(name = "pexelsSearchPhotos",
          description = """
                  Search Pexels for high-quality, licensed stock photos. \
                  Use when the user asks to find reference images, browse photos \
                  on a topic, or get visual inspiration. Pexels provides professional \
                  photography with rich metadata (color palette, photographer, description). \
                  Results are displayed through an image_candidates event in the UI. \
                  For downloading and saving images to the gallery, use pexelsSearchAndImport.""")
    public String pexelsSearchPhotos(
            @ToolParam(required = true,
                       description = "Search query, e.g. 'mountain sunset', 'cyberpunk city', 'minimalist interior'")
            String query,
            @ToolParam(required = false,
                       description = "Photo orientation: landscape, portrait, or square")
            String orientation,
            @ToolParam(required = false,
                       description = "Minimum photo size: large (24MP), medium (12MP), or small (4MP)")
            String size,
            @ToolParam(required = false,
                       description = "Dominant color: red, orange, yellow, green, turquoise, blue, violet, pink, brown, black, gray, white, or hex code like #FF6347")
            String color,
            @ToolParam(required = false,
                       description = "Number of results (1-10, default 5)")
            Integer limit,
            ToolContext toolContext) {

        int perPage = Math.clamp(limit != null ? limit : 5, 1, 10);
        progressContext.start(toolContext, "pexelsSearchPhotos", "正在搜索 Pexels 图库：" + query);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("query", query, "perPage", perPage, "orientation", orientation != null ? orientation : "", "color", color != null ? color : "");
        taskLedger.beforeCall(turnId, "pexelsSearchPhotos", input);

        try {
            List<Map<String, Object>> photos;
            if (mcpProperties.isMcpMode()) {
                photos = imageRetrievalGateway.searchPexels(query, perPage, 1);
            } else {
                photos = pexelsPhotoService.search(
                        new com.zzp.aiagent.domain.pexels.PexelsSearchRequest(
                                query, perPage, 1, orientation, size, color, "zh-CN"))
                        .photos().stream()
                        .map(PexelsSearchTools::toPhotoMap)
                        .toList();
            }

            if (photos.isEmpty()) {
                taskLedger.recordSuccess(turnId, "pexelsSearchPhotos", input, Map.of("candidateCount", 0), ToolExecutionRecord.NONE);
                progressContext.done(toolContext, "pexelsSearchPhotos",
                        "Pexels 未找到与「" + query + "」相关的图片");
                return "Pexels 未找到与「" + query + "」相关的图片。请尝试更具体或不同的搜索词。";
            }

            // Push to frontend via image_candidates event
            List<ImageCandidateVO> candidates = photos.stream()
                    .map(PexelsSearchTools::toCandidateVO)
                    .toList();
            progressContext.imageCandidates(toolContext,
                    new ImageCandidatesEventVO(query, "pexels", candidates));

            // Return text summary for the Agent
            StringBuilder sb = new StringBuilder("Pexels 搜索「").append(query)
                    .append("」找到 ").append(photos.size()).append(" 张图片：\n");
            for (int i = 0; i < photos.size(); i++) {
                Map<String, Object> p = photos.get(i);
                appendPhotoSummary(sb, i + 1, p);
            }

            progressContext.done(toolContext, "pexelsSearchPhotos",
                    "已找到 " + photos.size() + " 张 Pexels 图片");
            Map<String, Object> output = Map.of("candidateCount", photos.size());
            taskLedger.recordSuccess(turnId, "pexelsSearchPhotos", input, output, ToolExecutionRecord.IMAGE_CANDIDATES_RETURNED);
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 搜索失败 queryLength={}", query != null ? query.length() : 0, e);
            progressContext.fail(toolContext, "pexelsSearchPhotos", e.getMessage());
            taskLedger.recordFailure(turnId, "pexelsSearchPhotos", input, e.getMessage());
            return "Pexels 图片搜索失败：" + e.getMessage();
        }
    }

    // ── 浏览精选照片（不入库）─────────────────────────────────────────

    @Tool(name = "pexelsCuratedPhotos",
          description = """
                  Browse Pexels editor-curated featured photos. \
                  Use when the user wants inspiration, asks "what's trending", \
                  or wants to explore high-quality photography without a specific \
                  search term. Results are displayed through an image_candidates event.""")
    public String pexelsCuratedPhotos(
            @ToolParam(required = false,
                       description = "Number of results (1-10, default 5)")
            Integer limit,
            ToolContext toolContext) {

        int perPage = Math.clamp(limit != null ? limit : 5, 1, 10);
        progressContext.start(toolContext, "pexelsCuratedPhotos", "正在浏览 Pexels 精选照片");
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("perPage", perPage);
        taskLedger.beforeCall(turnId, "pexelsCuratedPhotos", input);

        try {
            List<Map<String, Object>> photos;
            if (mcpProperties.isMcpMode()) {
                photos = imageRetrievalGateway.curatedPexels(perPage, 1);
            } else {
                photos = pexelsPhotoService.curated(perPage, 1)
                        .photos().stream()
                        .map(PexelsSearchTools::toPhotoMap)
                        .toList();
            }

            if (photos.isEmpty()) {
                taskLedger.recordSuccess(turnId, "pexelsCuratedPhotos", input, Map.of("candidateCount", 0), ToolExecutionRecord.NONE);
                progressContext.done(toolContext, "pexelsCuratedPhotos", "Pexels 暂无精选照片");
                return "Pexels 暂无精选照片，请稍后再试。";
            }

            List<ImageCandidateVO> candidates = photos.stream()
                    .map(PexelsSearchTools::toCandidateVO)
                    .toList();
            progressContext.imageCandidates(toolContext,
                    new ImageCandidatesEventVO("curated", "pexels", candidates));

            StringBuilder sb = new StringBuilder("Pexels 精选照片（")
                    .append(photos.size()).append(" 张）：\n");
            for (int i = 0; i < photos.size(); i++) {
                Map<String, Object> p = photos.get(i);
                appendPhotoSummaryBrief(sb, i + 1, p);
            }

            progressContext.done(toolContext, "pexelsCuratedPhotos",
                    "已浏览 " + photos.size() + " 张精选照片");
            taskLedger.recordSuccess(turnId, "pexelsCuratedPhotos", input, Map.of("candidateCount", photos.size()), ToolExecutionRecord.IMAGE_CANDIDATES_RETURNED);
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 精选照片获取失败", e);
            progressContext.fail(toolContext, "pexelsCuratedPhotos", e.getMessage());
            taskLedger.recordFailure(turnId, "pexelsCuratedPhotos", input, e.getMessage());
            return "Pexels 精选照片获取失败：" + e.getMessage();
        }
    }

    // ── 搜索 + 下载入库 ──────────────────────────────────────────────

    @Tool(name = "pexelsSearchAndImport",
          description = """
                  Search Pexels and automatically download matching photos into \
                  the gallery. Use when the user explicitly wants to collect or save \
                  reference images from Pexels. Downloaded images are saved to MAIN \
                  storage (permanent). For browsing without saving, use pexelsSearchPhotos.""")
    public String pexelsSearchAndImport(
            @ToolParam(required = true,
                       description = "Search query, e.g. 'cyberpunk city concept art', 'minimalist interior design'")
            String query,
            @ToolParam(required = false,
                       description = "Number of images to download (1-5, default 3)")
            Integer count,
            @ToolParam(required = false,
                       description = "Photo orientation: landscape, portrait, or square")
            String orientation,
            @ToolParam(required = false,
                       description = "Minimum photo size: large, medium, or small")
            String size,
            @ToolParam(required = false,
                       description = "Dominant color filter")
            String color,
            ToolContext toolContext) {

        int n = Math.clamp(count != null ? count : 3, 1, 5);
        int searchLimit = Math.min(n * 4, 20);
        progressContext.start(toolContext, "pexelsSearchAndImport",
                "正在搜索并下载 Pexels 图片：" + query + "，目标 " + n + " 张");
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("query", query, "count", n, "orientation", orientation != null ? orientation : "");
        taskLedger.beforeCall(turnId, "pexelsSearchAndImport", input);

        try {
            List<Map<String, Object>> photos;
            if (mcpProperties.isMcpMode()) {
                photos = imageRetrievalGateway.searchPexels(query, searchLimit, 1);
            } else {
                photos = pexelsPhotoService.search(
                        new com.zzp.aiagent.domain.pexels.PexelsSearchRequest(
                                query, searchLimit, 1, orientation, size, color, "zh-CN"))
                        .photos().stream()
                        .map(PexelsSearchTools::toPhotoMap)
                        .toList();
            }

            if (photos.isEmpty()) {
                taskLedger.recordSuccess(turnId, "pexelsSearchAndImport", input, Map.of("savedCount", 0), ToolExecutionRecord.NONE);
                progressContext.done(toolContext, "pexelsSearchAndImport",
                        "Pexels 未找到与「" + query + "」相关的图片");
                return "Pexels 未找到与「" + query + "」相关的图片可下载。";
            }

            progressContext.progress(toolContext,
                    "找到 " + photos.size() + " 张 Pexels 候选，开始下载");

            List<String> saved = new ArrayList<>();
            for (Map<String, Object> photo : photos) {
                if (saved.size() >= n) break;
                try {
                    progressContext.progress(toolContext,
                            "正在下载第 " + (saved.size() + 1) + "/" + n + " 张图片");
                    String downloadUrl = pickDownloadUrl(photo);
                    byte[] bytes = pexelsPhotoService.downloadPhoto(downloadUrl);
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    String alt = stringVal(photo, "alt");
                    String picName = sanitizeName(alt);
                    String photoUrl = stringVal(photo, "url");
                    GalleryUploadRequest uploadReq = new GalleryUploadRequest(
                            base64, picName, photoUrl,
                            SOURCE_PEXELS, List.of(query), null, StorageLocation.MAIN);
                    GalleryPicture savedPic = galleryService.upload(uploadReq);
                    saved.add("[ID:" + savedPic.id() + "] " + savedPic.name());
                    progressContext.progress(toolContext,
                            "已保存第 " + saved.size() + " 张图片到图库 [ID:" + savedPic.id() + "]");
                } catch (Exception e) {
                    Object photoId = photo.get("id");
                    log.debug("[PexelsTools] 下载图片失败 photoId={}: {}", photoId, e.getMessage());
                }
            }

            if (saved.isEmpty()) {
                taskLedger.recordSuccess(turnId, "pexelsSearchAndImport", input, Map.of("savedCount", 0), ToolExecutionRecord.NONE);
                progressContext.done(toolContext, "pexelsSearchAndImport",
                        "未能下载到有效图片：" + query);
                return "搜索了「" + query + "」但未能下载到有效图片。请尝试其他搜索词。";
            }

            progressContext.done(toolContext, "pexelsSearchAndImport",
                    "已下载 " + saved.size() + " 张 Pexels 图片入库");
            Map<String, Object> output = Map.of("savedCount", saved.size());
            taskLedger.recordSuccess(turnId, "pexelsSearchAndImport", input, output, ToolExecutionRecord.GALLERY_CREATED);
            StringBuilder sb = new StringBuilder("已从 Pexels 搜索「").append(query)
                    .append("」并下载 ").append(saved.size()).append(" 张图片入库：\n");
            for (String s : saved) {
                sb.append("  - ").append(s).append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 搜索下载失败 queryLength={}", query != null ? query.length() : 0, e);
            progressContext.fail(toolContext, "pexelsSearchAndImport", e.getMessage());
            taskLedger.recordFailure(turnId, "pexelsSearchAndImport", input, e.getMessage());
            return "Pexels 图片搜索下载失败：" + e.getMessage();
        }
    }

    // ── 单张照片详情 ─────────────────────────────────────────────────

    @Tool(name = "pexelsGetPhoto",
          description = """
                  Get full metadata for a specific Pexels photo by its ID. \
                  Returns photographer, average color, description (alt text), \
                  dimensions, and available image sizes. Use when the user wants \
                  detailed information about a photo found via pexelsSearchPhotos.""")
    public String pexelsGetPhoto(
            @ToolParam(required = true,
                       description = "The Pexels photo ID")
            Long photoId,
            ToolContext toolContext) {

        progressContext.start(toolContext, "pexelsGetPhoto",
                "正在获取 Pexels 照片详情 ID:" + photoId);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("photoId", photoId);
        taskLedger.beforeCall(turnId, "pexelsGetPhoto", input);

        try {
            Map<String, Object> photo;
            if (mcpProperties.isMcpMode()) {
                photo = imageRetrievalGateway.getPexelsPhoto(photoId.intValue());
            } else {
                photo = toPhotoMap(pexelsPhotoService.getPhoto(photoId));
            }

            if (photo.isEmpty()) {
                taskLedger.recordSuccess(turnId, "pexelsGetPhoto", input, Map.of(), ToolExecutionRecord.NONE);
                progressContext.done(toolContext, "pexelsGetPhoto",
                        "Pexels 照片详情获取失败：未找到该照片");
                return "Pexels 未找到 ID 为 " + photoId + " 的照片。";
            }

            long id = longVal(photo, "id");
            StringBuilder sb = new StringBuilder("Pexels 照片详情 [ID:").append(id).append("]\n");
            appendField(sb, "描述", stringVal(photo, "alt"));
            appendField(sb, "摄影师", stringVal(photo, "photographer"));
            appendField(sb, "摄影师主页", stringVal(photo, "photographerUrl"));
            appendField(sb, "Pexels 页面", stringVal(photo, "url"));
            sb.append("尺寸：").append(intVal(photo, "width")).append("×").append(intVal(photo, "height")).append("\n");
            appendField(sb, "主色调", stringVal(photo, "avgColor"));

            @SuppressWarnings("unchecked")
            Map<String, Object> src = (Map<String, Object>) photo.get("src");
            sb.append("\n可用尺寸：\n");
            appendUrl(sb, "原图", stringVal(src, "original"));
            appendUrl(sb, "大图 2x", stringVal(src, "large2x"));
            appendUrl(sb, "大图", stringVal(src, "large"));
            appendUrl(sb, "中图", stringVal(src, "medium"));
            appendUrl(sb, "小图", stringVal(src, "small"));
            appendUrl(sb, "竖版裁剪", stringVal(src, "portrait"));
            appendUrl(sb, "横版裁剪", stringVal(src, "landscape"));
            appendUrl(sb, "缩略图", stringVal(src, "tiny"));

            progressContext.done(toolContext, "pexelsGetPhoto",
                    "Pexels 照片详情已获取 ID:" + photoId);
            taskLedger.recordSuccess(turnId, "pexelsGetPhoto", input, Map.of(), ToolExecutionRecord.NONE);
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 获取照片详情失败 photoId={}", photoId, e);
            progressContext.fail(toolContext, "pexelsGetPhoto", e.getMessage());
            taskLedger.recordFailure(turnId, "pexelsGetPhoto", input, e.getMessage());
            return "Pexels 照片详情获取失败：" + e.getMessage();
        }
    }

    // ── Helpers (Map-based, works for both local and MCP photo data) ─

    /**
     * Convert a PexelsPhoto domain object to a Map for unified processing.
     */
    static Map<String, Object> toPhotoMap(com.zzp.aiagent.domain.pexels.PexelsPhoto photo) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", photo.id());
        map.put("width", photo.width());
        map.put("height", photo.height());
        map.put("alt", photo.alt());
        map.put("photographer", photo.photographer());
        map.put("photographerUrl", photo.photographerUrl());
        map.put("url", photo.url());
        map.put("avgColor", photo.avgColor());
        if (photo.src() != null) {
            Map<String, String> smap = new java.util.LinkedHashMap<>();
            smap.put("original", photo.src().original());
            smap.put("large2x", photo.src().large2x());
            smap.put("large", photo.src().large());
            smap.put("medium", photo.src().medium());
            smap.put("small", photo.src().small());
            smap.put("portrait", photo.src().portrait());
            smap.put("landscape", photo.src().landscape());
            smap.put("tiny", photo.src().tiny());
            map.put("src", smap);
        }
        return map;
    }

    private static ImageCandidateVO toCandidateVO(Map<String, Object> photo) {
        @SuppressWarnings("unchecked")
        Map<String, Object> src = (Map<String, Object>) photo.get("src");
        String displayUrl;
        if (src != null) {
            String medium = stringVal(src, "medium");
            String large = stringVal(src, "large");
            displayUrl = !medium.isBlank() ? medium : large;
        } else {
            displayUrl = "";
        }
        String alt = stringVal(photo, "alt");
        String photographer = stringVal(photo, "photographer");
        return new ImageCandidateVO(
                !alt.isBlank() ? alt : photographer + " 作品",
                displayUrl,
                stringVal(photo, "url"),
                "pexels",
                1.0
        );
    }

    private static void appendPhotoSummary(StringBuilder sb, int index, Map<String, Object> p) {
        sb.append(index).append(". [Pexels ID:").append(longVal(p, "id")).append("] ");
        String alt = stringVal(p, "alt");
        if (!alt.isBlank()) {
            sb.append(alt);
        } else {
            sb.append(stringVal(p, "photographer")).append(" 作品");
        }
        String avgColor = stringVal(p, "avgColor");
        if (!avgColor.isBlank()) {
            sb.append(" | 主色调 ").append(avgColor);
        }
        sb.append(" | 尺寸 ").append(intVal(p, "width")).append("×").append(intVal(p, "height"));
        sb.append(" | © ").append(stringVal(p, "photographer"));
        sb.append("\n");
    }

    private static void appendPhotoSummaryBrief(StringBuilder sb, int index, Map<String, Object> p) {
        sb.append(index).append(". [ID:").append(longVal(p, "id")).append("] ");
        String alt = stringVal(p, "alt");
        sb.append(!alt.isBlank() ? alt : "无标题");
        sb.append(" | © ").append(stringVal(p, "photographer"));
        String avgColor = stringVal(p, "avgColor");
        if (!avgColor.isBlank()) {
            sb.append(" | ").append(avgColor);
        }
        sb.append("\n");
    }

    /**
     * Pick the best download URL from photo map: original > large2x > large > medium.
     */
    static String pickDownloadUrl(Map<String, Object> photo) {
        @SuppressWarnings("unchecked")
        Map<String, Object> src = (Map<String, Object>) photo.get("src");
        if (src == null) return "";
        String[] keys = {"original", "large2x", "large", "medium"};
        for (String key : keys) {
            String url = stringVal(src, key);
            if (!url.isBlank()) return url;
        }
        return stringVal(src, "original"); // fallback
    }

    /**
     * Pick the best download URL from a PexelsPhotoSrc domain object.
     * Kept for backward compatibility with existing callers.
     */
    public static String pickDownloadUrl(com.zzp.aiagent.domain.pexels.PexelsPhotoSrc src) {
        if (src.original() != null && !src.original().isBlank()) return src.original();
        if (src.large2x() != null && !src.large2x().isBlank()) return src.large2x();
        if (src.large() != null && !src.large().isBlank()) return src.large();
        if (src.medium() != null && !src.medium().isBlank()) return src.medium();
        return src.original() != null ? src.original() : "";
    }

    private static void appendUrl(StringBuilder sb, String label, String url) {
        if (url != null && !url.isBlank()) {
            sb.append("  ").append(label).append("：").append(url).append("\n");
        }
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    private static String sanitizeName(String alt) {
        if (alt == null || alt.isBlank()) {
            return "pexels-photo";
        }
        String cleaned = alt.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80) + "...";
        }
        return cleaned.isBlank() ? "pexels-photo" : cleaned;
    }

    private static String currentTurnId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        Object value = toolContext.getContext().get("turnId");
        return value instanceof String text ? text : null;
    }

    // ── Map accessor helpers ─

    private static String stringVal(Map<String, Object> map, String key) {
        if (map == null) return "";
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private static long longVal(Map<String, Object> map, String key) {
        Object v = map != null ? map.get(key) : null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object v = map != null ? map.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
