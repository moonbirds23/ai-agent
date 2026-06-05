package com.zzp.aiagent.tool;

import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.domain.pexels.PexelsPhoto;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import com.zzp.aiagent.domain.pexels.PexelsPhotoSrc;
import com.zzp.aiagent.domain.pexels.PexelsSearchRequest;
import com.zzp.aiagent.domain.pexels.PexelsSearchResult;
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
import java.util.Locale;

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

    public PexelsSearchTools(PexelsPhotoService pexelsPhotoService,
                             GalleryService galleryService,
                             ToolProgressContext progressContext) {
        this.pexelsPhotoService = pexelsPhotoService;
        this.galleryService = galleryService;
        this.progressContext = progressContext;
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

        try {
            PexelsSearchRequest request = new PexelsSearchRequest(
                    query, perPage, 1, orientation, size, color, "zh-CN");
            PexelsSearchResult result = pexelsPhotoService.search(request);

            if (result.photos().isEmpty()) {
                progressContext.done(toolContext, "pexelsSearchPhotos",
                        "Pexels 未找到与「" + query + "」相关的图片");
                return "Pexels 未找到与「" + query + "」相关的图片。请尝试更具体或不同的搜索词。";
            }

            // Push to frontend via image_candidates event
            List<ImageCandidateVO> candidates = result.photos().stream()
                    .map(PexelsSearchTools::toCandidateVO)
                    .toList();
            progressContext.imageCandidates(toolContext,
                    new ImageCandidatesEventVO(query, "pexels", candidates));

            // Return text summary for the Agent
            StringBuilder sb = new StringBuilder("Pexels 搜索「").append(query)
                    .append("」找到 ").append(result.totalResults()).append(" 张图片，")
                    .append("返回前 ").append(result.photos().size()).append(" 张：\n");
            for (int i = 0; i < result.photos().size(); i++) {
                PexelsPhoto p = result.photos().get(i);
                sb.append(i + 1).append(". [ID:").append(p.id()).append("] ");
                if (p.alt() != null && !p.alt().isBlank()) {
                    sb.append(p.alt());
                } else {
                    sb.append(p.photographer()).append(" 作品");
                }
                if (p.avgColor() != null && !p.avgColor().isBlank()) {
                    sb.append(" | 主色调 ").append(p.avgColor());
                }
                sb.append(" | 尺寸 ").append(p.width()).append("×").append(p.height());
                sb.append(" | © ").append(p.photographer());
                sb.append("\n");
            }

            progressContext.done(toolContext, "pexelsSearchPhotos",
                    "已找到 " + result.photos().size() + " 张 Pexels 图片");
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 搜索失败 query={}", query, e);
            progressContext.fail(toolContext, "pexelsSearchPhotos", e.getMessage());
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

        try {
            PexelsSearchResult result = pexelsPhotoService.curated(perPage, 1);

            if (result.photos().isEmpty()) {
                progressContext.done(toolContext, "pexelsCuratedPhotos", "Pexels 暂无精选照片");
                return "Pexels 暂无精选照片，请稍后再试。";
            }

            List<ImageCandidateVO> candidates = result.photos().stream()
                    .map(PexelsSearchTools::toCandidateVO)
                    .toList();
            progressContext.imageCandidates(toolContext,
                    new ImageCandidatesEventVO("curated", "pexels", candidates));

            StringBuilder sb = new StringBuilder("Pexels 精选照片（")
                    .append(result.photos().size()).append(" 张）：\n");
            for (int i = 0; i < result.photos().size(); i++) {
                PexelsPhoto p = result.photos().get(i);
                sb.append(i + 1).append(". [ID:").append(p.id()).append("] ");
                sb.append(p.alt() != null && !p.alt().isBlank() ? p.alt() : "无标题");
                sb.append(" | © ").append(p.photographer());
                if (p.avgColor() != null && !p.avgColor().isBlank()) {
                    sb.append(" | ").append(p.avgColor());
                }
                sb.append("\n");
            }

            progressContext.done(toolContext, "pexelsCuratedPhotos",
                    "已浏览 " + result.photos().size() + " 张精选照片");
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 精选照片获取失败", e);
            progressContext.fail(toolContext, "pexelsCuratedPhotos", e.getMessage());
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

        try {
            PexelsSearchRequest request = new PexelsSearchRequest(
                    query, searchLimit, 1, orientation, size, color, "zh-CN");
            PexelsSearchResult result = pexelsPhotoService.search(request);

            if (result.photos().isEmpty()) {
                progressContext.done(toolContext, "pexelsSearchAndImport",
                        "Pexels 未找到与「" + query + "」相关的图片");
                return "Pexels 未找到与「" + query + "」相关的图片可下载。";
            }

            progressContext.progress(toolContext,
                    "找到 " + result.photos().size() + " 张 Pexels 候选，开始下载");

            List<String> saved = new ArrayList<>();
            for (PexelsPhoto photo : result.photos()) {
                if (saved.size() >= n) break;
                try {
                    progressContext.progress(toolContext,
                            "正在下载第 " + (saved.size() + 1) + "/" + n + " 张图片");
                    String downloadUrl = pickDownloadUrl(photo.src());
                    byte[] bytes = pexelsPhotoService.downloadPhoto(downloadUrl);
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    String picName = sanitizeName(photo.alt());
                    GalleryUploadRequest uploadReq = new GalleryUploadRequest(
                            base64, picName, photo.url(),
                            SOURCE_PEXELS, List.of(query), null, StorageLocation.MAIN);
                    GalleryPicture savedPic = galleryService.upload(uploadReq);
                    saved.add("[ID:" + savedPic.id() + "] " + savedPic.name());
                    progressContext.progress(toolContext,
                            "已保存第 " + saved.size() + " 张图片到图库 [ID:" + savedPic.id() + "]");
                } catch (Exception e) {
                    log.debug("[PexelsTools] 下载图片失败 photoId={}: {}", photo.id(), e.getMessage());
                }
            }

            if (saved.isEmpty()) {
                progressContext.done(toolContext, "pexelsSearchAndImport",
                        "未能下载到有效图片：" + query);
                return "搜索了「" + query + "」但未能下载到有效图片。请尝试其他搜索词。";
            }

            progressContext.done(toolContext, "pexelsSearchAndImport",
                    "已下载 " + saved.size() + " 张 Pexels 图片入库");
            StringBuilder sb = new StringBuilder("已从 Pexels 搜索「").append(query)
                    .append("」并下载 ").append(saved.size()).append(" 张图片入库：\n");
            for (String s : saved) {
                sb.append("  - ").append(s).append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 搜索下载失败 query={}", query, e);
            progressContext.fail(toolContext, "pexelsSearchAndImport", e.getMessage());
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

        try {
            PexelsPhoto photo = pexelsPhotoService.getPhoto(photoId);

            StringBuilder sb = new StringBuilder("Pexels 照片详情 [ID:").append(photo.id()).append("]\n");
            if (photo.alt() != null && !photo.alt().isBlank()) {
                sb.append("描述：").append(photo.alt()).append("\n");
            }
            sb.append("摄影师：").append(photo.photographer()).append("\n");
            sb.append("摄影师主页：").append(photo.photographerUrl()).append("\n");
            sb.append("Pexels 页面：").append(photo.url()).append("\n");
            sb.append("尺寸：").append(photo.width()).append("×").append(photo.height()).append("\n");
            if (photo.avgColor() != null && !photo.avgColor().isBlank()) {
                sb.append("主色调：").append(photo.avgColor()).append("\n");
            }

            PexelsPhotoSrc src = photo.src();
            sb.append("\n可用尺寸：\n");
            appendUrl(sb, "原图", src.original());
            appendUrl(sb, "大图 2x", src.large2x());
            appendUrl(sb, "大图", src.large());
            appendUrl(sb, "中图", src.medium());
            appendUrl(sb, "小图", src.small());
            appendUrl(sb, "竖版裁剪", src.portrait());
            appendUrl(sb, "横版裁剪", src.landscape());
            appendUrl(sb, "缩略图", src.tiny());

            progressContext.done(toolContext, "pexelsGetPhoto",
                    "Pexels 照片详情已获取 ID:" + photoId);
            return sb.toString();

        } catch (Exception e) {
            log.error("[PexelsTools] 获取照片详情失败 photoId={}", photoId, e);
            progressContext.fail(toolContext, "pexelsGetPhoto", e.getMessage());
            return "Pexels 照片详情获取失败：" + e.getMessage();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static ImageCandidateVO toCandidateVO(PexelsPhoto photo) {
        String displayUrl = photo.src().medium() != null && !photo.src().medium().isBlank()
                ? photo.src().medium()
                : photo.src().large();
        return new ImageCandidateVO(
                photo.alt() != null && !photo.alt().isBlank()
                        ? photo.alt()
                        : photo.photographer() + " 作品",
                displayUrl,
                photo.url(),
                "pexels",
                1.0  // Pexels results are uniformly high quality
        );
    }

    /**
     * Pick the best download URL: original > large2x > large > medium.
     */
    public static String pickDownloadUrl(PexelsPhotoSrc src) {
        if (src.original() != null && !src.original().isBlank()) return src.original();
        if (src.large2x() != null && !src.large2x().isBlank()) return src.large2x();
        if (src.large() != null && !src.large().isBlank()) return src.large();
        if (src.medium() != null && !src.medium().isBlank()) return src.medium();
        return src.original(); // fallback, let it error upstream
    }

    private static void appendUrl(StringBuilder sb, String label, String url) {
        if (url != null && !url.isBlank()) {
            sb.append("  ").append(label).append("：").append(url).append("\n");
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
}
