package com.zzp.aiagent.tool;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.common.UrlSecurityValidator;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.domain.web.WebProperties;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.model.vo.ImageCandidateVO;
import com.zzp.aiagent.model.vo.ImageCandidatesEventVO;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.service.GalleryService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Web-facing tools: search, fetch, download, import.
 * <p>
 * Downloaded images are saved to the gallery (MAIN, permanent).
 * Search results without download are ephemeral (not persisted).
 */
@Component
@Profile("!test")
@Slf4j
public class WebSearchTools {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_IMAGE_CANDIDATE_POOL = 50;
    private static final Set<String> POLLUTION_TERMS = Set.of(
            "招财", "来财", "发财", "头像", "qq头像", "表情包", "动态壁纸", "手机壁纸",
            "商品", "淘宝", "京东", "机械", "cad", "施工图", "图纸", "报价单", "logo"
    );
    private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
            "图片", "照片", "素材", "壁纸", "高清", "类型", "风格", "自然", "景观", "风景",
            "image", "images", "photo", "photos", "picture", "pictures", "wallpaper"
    );

    private final HttpClient httpClient;
    private final UrlSecurityValidator urlValidator;
    private final GalleryService galleryService;
    private final WebProperties props;
    private final ToolProgressContext progressContext;
    private final TaskLedger taskLedger;

    public WebSearchTools(UrlSecurityValidator urlValidator,
                          GalleryService galleryService,
                          WebProperties props,
                          ToolProgressContext progressContext,
                          TaskLedger taskLedger) {
        this.urlValidator = urlValidator;
        this.galleryService = galleryService;
        this.props = props;
        this.progressContext = progressContext;
        this.taskLedger = taskLedger;
        var clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (props.hasProxy()) {
            clientBuilder.proxy(ProxySelector.of(
                    new InetSocketAddress(props.proxyHost(), props.proxyPort())));
        }
        this.httpClient = clientBuilder.build();
    }

    // ── 网页搜索 ────────────────────────────────────────────────────

    @Tool(name = "webSearch",
          description = """
                  Search the internet using Bing Web Search. Returns page titles, URLs, and snippets. \
                  Use for current information, facts, and text web pages — not for image search. \
                  For finding image candidates, call imageSearch instead.""")
    public String webSearch(
            @ToolParam(required = true,
                       description = "Search query, e.g. 'latest AI image generation news'")
            String query,
            @ToolParam(required = false,
                       description = "Number of results (1-10, default 5)")
            Integer limit,
            @ToolParam(required = false,
                       description = "Search source: 'international' (Bing global, via proxy), 'domestic' (cn.bing.com, direct), or 'auto' (default, tries international first then falls back to domestic)")
            String source,
            ToolContext toolContext) {

        progressContext.start(toolContext, "webSearch", "正在搜索网页：" + query);
        int n = Math.clamp(limit != null ? limit : props.searchMaxResults(), 1, 10);
        String src = normalizeSource(source);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("query", query, "limit", n, "source", src);
        taskLedger.beforeCall(turnId, "webSearch", input);

        if ("auto".equals(src) || "international".equals(src)) {
            try {
                String result = doSearch(query, n, "www.bing.com", true, "Bing 国际版");
                progressContext.done(toolContext, "webSearch", "网页搜索完成：" + query);
                taskLedger.recordSuccess(turnId, "webSearch", input, Map.of(), ToolExecutionRecord.WEB_FETCHED);
                return result;
            } catch (Exception e) {
                log.warn("[WebSearch] 国际版搜索失败, queryLength={}: {}", query.length(), e.getMessage());
                if ("international".equals(src)) {
                    progressContext.fail(toolContext, "webSearch", e.getMessage());
                    taskLedger.recordFailure(turnId, "webSearch", input, e.getMessage());
                    return "Bing 国际版搜索失败（需代理）: " + e.getMessage() + "。可尝试 source=domestic 使用国内搜索。";
                }
                progressContext.progress(toolContext, "国际版网页搜索失败，尝试国内版");
            }
        }

        if ("auto".equals(src) || "domestic".equals(src)) {
            try {
                String result = doSearch(query, n, "cn.bing.com", false, "Bing 国内版");
                progressContext.done(toolContext, "webSearch", "网页搜索完成：" + query);
                taskLedger.recordSuccess(turnId, "webSearch", input, Map.of(), ToolExecutionRecord.WEB_FETCHED);
                return result;
            } catch (Exception e) {
                log.error("[WebSearch] 国内版搜索失败 queryLength={}: {}", query.length(), e.getMessage());
                progressContext.fail(toolContext, "webSearch", e.getMessage());
                taskLedger.recordFailure(turnId, "webSearch", input, e.getMessage());
                return "搜索失败: " + e.getMessage() + "。请稍后重试。";
            }
        }

        return "不支持的搜索源: " + src + "。可选值: auto, international, domestic";
    }

    /** Execute a single search against a Bing endpoint. */
    private String doSearch(String query, int n, String host, boolean useProxy, String sourceLabel) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://" + host + "/search?q=" + encoded + "&count=" + (n * 2) + "&setlang=zh-cn";
        String html = fetchSearchString(url, props.maxFetchBytes(), useProxy);
        Document doc = Jsoup.parse(html);

        List<SearchResult> results = new ArrayList<>();
        Elements items = doc.select("li.b_algo");
        for (Element item : items) {
            if (results.size() >= n) break;
            Element h2 = item.selectFirst("h2");
            Element link = h2 != null ? h2.selectFirst("a") : null;
            Element caption = item.selectFirst(".b_caption");
            Element snippet = caption != null ? caption.selectFirst("p") : null;
            if (link == null) continue;
            String title = link.text().strip();
            String href = link.attr("href");
            String desc = snippet != null ? snippet.text().strip() : "";
            if (title.isBlank() || href.isBlank()) continue;
            results.add(new SearchResult(title, href, desc));
        }

        if (results.isEmpty()) {
            return "【" + sourceLabel + "】未找到与「" + query + "」相关的搜索结果。请尝试更具体的关键词。";
        }

        StringBuilder sb = new StringBuilder("【" + sourceLabel + "】搜索「").append(query).append("」（共 ")
                .append(results.size()).append(" 条）：\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". **").append(r.title).append("**\n");
            sb.append("   URL: ").append(r.url).append("\n");
            if (!r.snippet.isBlank()) {
                sb.append("   摘要: ").append(r.snippet).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── 图片搜索（不入库）──────────────────────────────────────────────

    @Tool(name = "imageSearch",
          description = """
                  Search Bing Images and return image candidates without saving them. \
                  Use when the user asks to find, browse, or show similar photos/images/materials on the web. \
                  Search results are displayed by the system through a structured image_candidates event. \
                  Do not invent placeholder image links. If no candidates are returned, tell the user \
                  no displayable images were found. If the user wants to collect or download images \
                  into the gallery, use searchAndDownload.""")
    public String imageSearch(
            @ToolParam(required = true,
                       description = "Image search query, e.g. 'mountain lake landscape photography'")
            String query,
            @ToolParam(required = false,
                       description = "Number of image candidates (1-10, default 5)")
            Integer limit,
            @ToolParam(required = false,
                       description = "Search source: international/domestic/auto")
            String source,
            ToolContext toolContext) {

        int n = Math.clamp(limit != null ? limit : props.searchMaxResults(), 1, 10);
        String normalizedSource = normalizeSource(source);
        progressContext.start(toolContext, "imageSearch", "正在搜索网络图片：" + query);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("query", query, "limit", n, "source", normalizedSource);
        taskLedger.beforeCall(turnId, "imageSearch", input);
        List<ImageCandidate> candidates = searchImageCandidatesAuto(query, n, normalizedSource, toolContext);

        if (candidates.isEmpty()) {
            progressContext.done(toolContext, "imageSearch", "未找到可展示的网络图片候选：" + query);
            taskLedger.recordSuccess(turnId, "imageSearch", input, Map.of("candidateCount", 0), ToolExecutionRecord.NONE);
            return "未找到与「" + query + "」相关的图片候选。请尝试更具体的搜索词。";
        }

        List<ImageCandidateVO> candidateVOs = candidates.stream()
                .map(c -> new ImageCandidateVO(c.title(), c.imageUrl(), c.sourceUrl(), c.parseSource(), c.score()))
                .toList();
        progressContext.imageCandidates(toolContext, new ImageCandidatesEventVO(query, normalizedSource, candidateVOs));
        progressContext.done(toolContext, "imageSearch", "找到 " + candidates.size() + " 个网络图片候选");
        Map<String, Object> output = Map.of("candidateCount", candidates.size());
        taskLedger.recordSuccess(turnId, "imageSearch", input, output, ToolExecutionRecord.IMAGE_CANDIDATES_RETURNED);
        return "已找到 " + candidates.size() + " 个与「" + query
                + "」相关的网络图片候选，并已在界面中展示。请简要说明搜索结果，不要重复列出图片 URL。";
    }

    // ── 网页抓取 ────────────────────────────────────────────────────

    @Tool(name = "webFetch",
          description = """
                  Fetch and extract plain text from a web page. Use when the user asks \
                  you to read, summarize, or analyze content from a specific URL. \
                  Returns page title and clean text (HTML tags stripped, truncated).""")
    public String webFetch(
            @ToolParam(required = true,
                       description = "The full URL of the page to fetch, e.g. 'https://example.com/article'")
            String url,
            @ToolParam(required = false,
                       description = "Max characters to return (1-8000, default 3000)")
            Integer maxChars,
            ToolContext toolContext) {

        progressContext.start(toolContext, "webFetch", "正在抓取网页：" + url);
        int max = Math.clamp(maxChars != null ? maxChars : props.fetchMaxChars(), 1, 8000);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("url", url, "maxChars", max);
        taskLedger.beforeCall(turnId, "webFetch", input);
        try {
            urlValidator.validate(url);
            String html = fetchString(url, props.maxFetchBytes());
            Document doc = Jsoup.parse(html);
            String title = doc.title();
            String text = doc.body() != null ? doc.body().text() : "";
            if (text.length() > max) text = text.substring(0, max) + "...（已截断）";

            StringBuilder sb = new StringBuilder();
            if (title != null && !title.isBlank()) {
                sb.append("## ").append(title).append("\n\n");
            }
            sb.append(text);
            progressContext.done(toolContext, "webFetch", "网页抓取完成");
            taskLedger.recordSuccess(turnId, "webFetch", input, Map.of(), ToolExecutionRecord.WEB_FETCHED);
            return sb.toString();

        } catch (BusinessException e) {
            progressContext.fail(toolContext, "webFetch", e.getMessage());
            taskLedger.recordFailure(turnId, "webFetch", input, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[WebFetch] 抓取失败 url={}", url, e);
            progressContext.fail(toolContext, "webFetch", e.getMessage());
            taskLedger.recordFailure(turnId, "webFetch", input, e.getMessage());
            return "网页抓取失败：" + e.getMessage();
        }
    }

    // ── 下载网络图片入库 ──────────────────────────────────────────────

    @Tool(name = "downloadImage",
          description = """
                  Download an image from a URL and permanently save it to the gallery. \
                  Use when the user wants to save an image from a direct image link. \
                  Downloaded images go to MAIN storage (permanent, not auto-cleaned).""")
    public String downloadImage(
            @ToolParam(required = true,
                       description = "The full URL of the image to download")
            String imageUrl,
            @ToolParam(required = false,
                       description = "Display name for the image in the gallery (auto-generated if empty)")
            String name,
            ToolContext toolContext) {

        progressContext.start(toolContext, "downloadImage", "正在下载图片：" + imageUrl);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("imageUrl", imageUrl, "name", name != null ? name : "");
        taskLedger.beforeCall(turnId, "downloadImage", input);
        try {
            URI uri = urlValidator.validate(imageUrl);
            byte[] bytes = downloadBytes(uri, 0, props.maxDownloadBytes());
            String contentType = detectContentType(bytes);
            ThrowUtils.throwIf(!contentType.startsWith("image/"),
                    ErrorCode.UNSUPPORTED_MEDIA_TYPE, "远程资源不是图片: " + contentType);

            String base64 = Base64.getEncoder().encodeToString(bytes);
            String picName = (name != null && !name.isBlank())
                    ? name : extractFilename(uri, contentType);
            GalleryUploadRequest req = new GalleryUploadRequest(
                    base64, picName, null, "web-download", null, null, StorageLocation.MAIN);
            GalleryPicture saved = galleryService.upload(req);

            log.info("[WebSearch] 图片下载入库 pictureId={} name={} url={}", saved.id(), saved.name(), imageUrl);
            progressContext.done(toolContext, "downloadImage", "图片已下载入库 [ID:" + saved.id() + "] " + saved.name());
            Map<String, Object> output = Map.of("pictureId", saved.id(), "pictureName", saved.name());
            taskLedger.recordSuccess(turnId, "downloadImage", input, output, ToolExecutionRecord.GALLERY_CREATED);
            return "图片已下载并保存到图库 [ID:" + saved.id() + "] " + saved.name();

        } catch (BusinessException e) {
            progressContext.fail(toolContext, "downloadImage", e.getMessage());
            taskLedger.recordFailure(turnId, "downloadImage", input, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[WebSearch] 图片下载失败 url={}", imageUrl, e);
            progressContext.fail(toolContext, "downloadImage", e.getMessage());
            taskLedger.recordFailure(turnId, "downloadImage", input, e.getMessage());
            return "图片下载失败：" + e.getMessage();
        }
    }

    // ── 搜索 + 自动下载 ──────────────────────────────────────────────

    @Tool(name = "searchAndDownload",
          description = """
                  Search Bing Images and automatically download matching image candidates into the gallery. \
                  Use when the user explicitly wants to collect/download reference images. \
                  For browsing image candidates without saving, use imageSearch.""")
    public String searchAndDownload(
            @ToolParam(required = true,
                       description = "Image search query, e.g. 'cyberpunk city concept art'")
            String query,
            @ToolParam(required = false,
                       description = "Number of images to download (1-5, default 3)")
            Integer count,
            ToolContext toolContext) {

        int n = Math.clamp(count != null ? count : 3, 1, 5);
        progressContext.start(toolContext, "searchAndDownload", "正在搜索并下载网络图片：" + query + "，目标 " + n + " 张");
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("query", query, "count", n);
        taskLedger.beforeCall(turnId, "searchAndDownload", input);
        List<ImageCandidate> candidates = searchImageCandidatesAuto(query, Math.min(n * 5, 20), "auto", toolContext);
        progressContext.progress(toolContext, "找到 " + candidates.size() + " 个图片候选，开始下载");

        List<String> saved = new ArrayList<>();
        int attempt = 0;
        for (ImageCandidate candidate : candidates) {
            if (saved.size() >= n) break;
            attempt++;
            try {
                progressContext.progress(toolContext, "正在下载第 " + (saved.size() + 1) + "/" + n + " 张候选图片");
                URI uri = urlValidator.validate(candidate.imageUrl());
                byte[] bytes = downloadBytes(uri, 0, props.maxDownloadBytes());
                String ct = detectContentType(bytes);
                if (!ct.startsWith("image/")) {
                    log.debug("[WebSearch] 跳过非图片候选 ct={} url={}", ct, candidate.imageUrl());
                    continue;
                }
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String picName = !candidate.title().isBlank() ? sanitizeName(candidate.title()) : extractFilename(uri, ct);
                GalleryUploadRequest req = new GalleryUploadRequest(
                        base64, picName, candidate.sourceUrl(), "web-search", List.of(query), null, StorageLocation.MAIN);
                GalleryPicture savedPic = galleryService.upload(req);
                saved.add("[ID:" + savedPic.id() + "] " + savedPic.name());
                progressContext.progress(toolContext, "已保存第 " + saved.size() + " 张图片到图库 [ID:" + savedPic.id() + "]");
            } catch (Exception e) {
                log.debug("[WebSearch] 下载图片候选失败 attempt={} url={}: {}", attempt, candidate.imageUrl(), e.getMessage());
            }
        }

        if (saved.isEmpty()) {
            progressContext.done(toolContext, "searchAndDownload", "未能下载到有效图片：" + query);
            return "搜索了「" + query + "」但未能下载到有效图片。请尝试更具体的搜索词，或使用 imageSearch 先浏览图片候选。";
        }

        progressContext.done(toolContext, "searchAndDownload", "已下载 " + saved.size() + " 张图片入库");
        Map<String, Object> output = Map.of("savedCount", saved.size());
        taskLedger.recordSuccess(turnId, "searchAndDownload", input, output, ToolExecutionRecord.GALLERY_CREATED);
        StringBuilder sb = new StringBuilder("已从网络搜索「").append(query)
                .append("」并下载 ").append(saved.size()).append(" 张图片入库：\n");
        for (String s : saved) {
            sb.append("  - ").append(s).append("\n");
        }
        return sb.toString();
    }

    private List<ImageCandidate> searchImageCandidatesAuto(String query, int limit, String source, ToolContext toolContext) {
        int candidatePool = Math.clamp(limit * 8, Math.max(limit, 10), MAX_IMAGE_CANDIDATE_POOL);
        List<ImageCandidate> results = new ArrayList<>();
        if ("auto".equals(source) || "international".equals(source)) {
            try {
                progressContext.progress(toolContext, "正在搜索 Bing 图片国际版：" + query);
                results = searchImageCandidates(query, limit, candidatePool, "www.bing.com", true);
            } catch (Exception e) {
                log.warn("[WebImageSearch] 国际版图片搜索失败 queryLength={}: {}", query.length(), e.getMessage());
                if ("international".equals(source)) {
                    return List.of();
                }
                progressContext.progress(toolContext, "国际版图片搜索失败，尝试国内版");
            }
        }
        if (results.isEmpty() && ("auto".equals(source) || "domestic".equals(source))) {
            try {
                progressContext.progress(toolContext, "正在搜索 Bing 图片国内版：" + query);
                results = searchImageCandidates(query, limit, candidatePool, "cn.bing.com", false);
            } catch (Exception e) {
                log.warn("[WebImageSearch] 国内版图片搜索失败 queryLength={}: {}", query.length(), e.getMessage());
            }
        }
        return results.stream().limit(limit).toList();
    }

    private List<ImageCandidate> searchImageCandidates(String query, int limit, int candidatePool,
                                                       String host, boolean useProxy) throws Exception {
        List<RawImageCandidate> rawCandidates = new ArrayList<>();
        if (props.imageSearchAsyncEnabled()) {
            rawCandidates.addAll(fetchAndParseImageCandidates(query, candidatePool, host, useProxy, "async"));
        }
        if (rawCandidates.size() < candidatePool) {
            rawCandidates.addAll(fetchAndParseImageCandidates(query, candidatePool, host, useProxy, "search"));
        }
        List<ImageCandidate> ranked = rankAndFilterCandidates(query, rawCandidates, limit);
        if (props.imageSearchDebug()) {
            log.info("[WebImageSearch] final queryLength={} host={} raw={} ranked={} top={}",
                    query.length(), host, rawCandidates.size(), ranked.size(), summarizeCandidates(ranked));
        }
        return ranked;
    }

    private List<RawImageCandidate> fetchAndParseImageCandidates(String query, int candidatePool,
                                                                 String host, boolean useProxy,
                                                                 String endpoint) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = switch (endpoint) {
            case "async" -> "https://" + host + "/images/async?q=" + encoded
                    + "&mmasync=1&count=" + candidatePool + "&first=1&setlang=zh-cn";
            default -> "https://" + host + "/images/search?q=" + encoded
                    + "&count=" + candidatePool + "&setlang=zh-cn";
        };
        FetchedSearchPage page = fetchSearchPage(url, props.maxFetchBytes(), useProxy);
        Document doc = Jsoup.parse(page.body(), page.url());
        ImageSearchDebugStats stats = new ImageSearchDebugStats(query, host, endpoint, page.url(),
                page.status(), page.contentType(), page.body().length(), doc.title(),
                doc.select("a.iusc").size(), doc.select("img.mimg").size(), doc.select(".dgControl img.mimg").size());

        if (props.imageSearchDebug() && props.imageSearchSaveDebugHtml()) {
            saveDebugHtml(query, host, endpoint, page.body());
        }

        List<RawImageCandidate> candidates = new ArrayList<>();
        candidates.addAll(parseIuscCandidates(doc, candidatePool, stats));
        if ("async".equals(endpoint)) {
            candidates.addAll(parseScopedImageCandidates(doc, ".dgControl img.mimg", "async-dg", candidatePool, stats));
        } else {
            candidates.addAll(parseScopedImageCandidates(doc,
                    ".dgControl img.mimg, .imgpt img.mimg, a.iusc img.mimg", "scoped-img", candidatePool, stats));
        }
        stats.parsed = candidates.size();
        if (props.imageSearchDebug()) {
            log.info("[WebImageSearch] {}", stats);
        }
        return candidates;
    }

    private List<RawImageCandidate> parseIuscCandidates(Document doc, int candidatePool, ImageSearchDebugStats stats) {
        List<RawImageCandidate> candidates = new ArrayList<>();
        for (Element item : doc.select("a.iusc")) {
            if (candidates.size() >= candidatePool) break;
            String meta = item.attr("m");
            String imageUrl = extractJsonString(meta, "murl");
            if (imageUrl.isBlank()) continue;
            String sourceUrl = extractJsonString(meta, "purl");
            String title = extractJsonString(meta, "t");
            if (title.isBlank()) title = item.attr("aria-label");
            if (title.isBlank()) title = item.text();
            candidates.add(new RawImageCandidate(title.strip(), imageUrl, sourceUrl, "iusc"));
        }
        stats.iuscParsed = candidates.size();
        return candidates;
    }

    private List<RawImageCandidate> parseScopedImageCandidates(Document doc, String selector, String parseSource,
                                                               int candidatePool, ImageSearchDebugStats stats) {
        List<RawImageCandidate> candidates = new ArrayList<>();
        for (Element img : doc.select(selector)) {
            if (candidates.size() >= candidatePool) break;
            String src = firstNonBlank(img.attr("data-src"), img.attr("src"));
            if (src.isBlank()) continue;
            String title = firstNonBlank(img.attr("alt"), img.attr("title"));
            Element link = img.closest("a[href]");
            String sourceUrl = link != null ? link.attr("href") : "";
            candidates.add(new RawImageCandidate(title.strip(), src, sourceUrl, parseSource));
        }
        if ("async-dg".equals(parseSource)) {
            stats.asyncParsed = candidates.size();
        } else {
            stats.scopedParsed = candidates.size();
        }
        return candidates;
    }

    private List<ImageCandidate> rankAndFilterCandidates(String query, List<RawImageCandidate> rawCandidates, int limit) {
        List<String> terms = extractQueryTerms(query);
        Map<String, ImageCandidate> byUrl = new LinkedHashMap<>();
        Set<String> weakKeys = new LinkedHashSet<>();
        double minScore = props.imageSearchMinRelevanceScore();
        for (RawImageCandidate raw : rawCandidates) {
            NormalizedCandidate normalized = normalizeCandidate(raw);
            if (normalized == null) continue;
            if (containsPollution(normalized.title(), normalized.imageUrl(), normalized.sourceUrl())) continue;
            double score = scoreCandidate(normalized, terms, query);
            if (score < minScore) continue;
            String weakKey = weakDedupeKey(normalized);
            if (!weakKey.isBlank() && weakKeys.contains(weakKey)) continue;
            ImageCandidate candidate = new ImageCandidate(normalized.title(), normalized.imageUrl(),
                    normalized.sourceUrl(), normalized.parseSource(), score);
            byUrl.merge(normalized.imageUrl(), candidate,
                    (oldValue, newValue) -> newValue.score() > oldValue.score() ? newValue : oldValue);
            if (!weakKey.isBlank()) {
                weakKeys.add(weakKey);
            }
        }
        return byUrl.values().stream()
                .sorted(Comparator.comparingDouble(ImageCandidate::score).reversed())
                .limit(limit)
                .toList();
    }

    private NormalizedCandidate normalizeCandidate(RawImageCandidate raw) {
        String imageUrl = normalizeUrl(raw.imageUrl());
        if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) return null;
        URI imageUri;
        try {
            imageUri = urlValidator.validate(imageUrl);
        } catch (Exception e) {
            return null;
        }
        String sourceUrl = normalizeUrl(raw.sourceUrl());
        if (!sourceUrl.isBlank() && (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://"))) {
            try {
                sourceUrl = urlValidator.validate(sourceUrl).toString();
            } catch (Exception e) {
                sourceUrl = "";
            }
        } else {
            sourceUrl = "";
        }
        String title = raw.title() != null ? raw.title().replaceAll("[\\r\\n\\t]+", " ").strip() : "";
        return new NormalizedCandidate(title, imageUri.toString(), sourceUrl, raw.parseSource());
    }

    private double scoreCandidate(NormalizedCandidate candidate, List<String> terms, String query) {
        double score = switch (candidate.parseSource()) {
            case "iusc" -> 0.60;
            case "async-dg" -> 0.45;
            default -> 0.25;
        };
        String text = (candidate.title() + " " + candidate.sourceUrl() + " " + candidate.imageUrl()).toLowerCase(Locale.ROOT);
        String normalizedQuery = normalizeText(query);
        boolean fullQueryMatch = !normalizedQuery.isBlank() && text.contains(normalizedQuery);
        if (fullQueryMatch) {
            score += 0.40;
        }
        int matches = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                matches++;
            }
        }
        score += Math.min(matches, 5) * 0.15;
        if (!candidate.sourceUrl().isBlank()) score += 0.08;
        if (looksLikeImageUrl(candidate.imageUrl())) score += 0.10;
        if (isBingThumbnail(candidate.imageUrl())) score -= 0.25;
        if (candidate.title().isBlank()) score -= 0.12;
        if (!terms.isEmpty() && matches == 0 && !fullQueryMatch) score -= "iusc".equals(candidate.parseSource()) ? 0.50 : 0.35;
        return score;
    }

    private static List<String> extractQueryTerms(String query) {
        String normalized = normalizeText(query);
        if (normalized.isBlank()) return List.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : normalized.split("[^\\p{IsHan}a-z0-9]+")) {
            if (part.isBlank() || GENERIC_QUERY_TERMS.contains(part)) continue;
            if (containsCjk(part)) {
                if (part.length() <= 4) {
                    terms.add(part);
                }
                for (int i = 0; i + 2 <= part.length(); i++) {
                    String term = part.substring(i, i + 2);
                    if (!GENERIC_QUERY_TERMS.contains(term)) {
                        terms.add(term);
                    }
                }
            } else if (part.length() >= 3) {
                terms.add(part);
            }
        }
        return new ArrayList<>(terms);
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static boolean containsPollution(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value != null) sb.append(value).append(' ');
        }
        String text = sb.toString().toLowerCase(Locale.ROOT);
        for (String term : POLLUTION_TERMS) {
            if (text.contains(term)) return true;
        }
        return false;
    }

    private static String weakDedupeKey(NormalizedCandidate candidate) {
        String title = normalizeText(candidate.title());
        String sourceHost = hostOf(candidate.sourceUrl());
        if (title.isBlank() && sourceHost.isBlank()) return "";
        return sourceHost + "|" + title;
    }

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String cleaned = url.strip().replace("&amp;", "&");
        if (cleaned.startsWith("//")) cleaned = "https:" + cleaned;
        return cleaned.replaceAll("[)\\]}>'\"，。；;]+$", "");
    }

    private static String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static boolean looksLikeImageUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(png|jpg|jpeg|webp|gif|bmp)(\\?.*)?$")
                || lower.contains("/image") || lower.contains("img") || lower.contains("photo");
    }

    private static boolean isBingThumbnail(String url) {
        String host = hostOf(url).toLowerCase(Locale.ROOT);
        return host.endsWith("bing.net") || host.contains("bing.com");
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null ? uri.getHost() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String summarizeCandidates(List<ImageCandidate> candidates) {
        return candidates.stream()
                .limit(3)
                .map(c -> "{" + c.parseSource() + ",score=" + String.format(Locale.ROOT, "%.2f", c.score())
                        + ",host=" + hostOf(c.imageUrl()) + ",title=" + sanitizeName(c.title()) + "}")
                .toList()
                .toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private void saveDebugHtml(String query, String host, String endpoint, String html) {
        try {
            Files.createDirectories(Path.of("logs"));
            String safeQuery = query.replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "_");
            if (safeQuery.length() > 40) safeQuery = safeQuery.substring(0, 40);
            String fileName = "bing-image-search-" + host.replace('.', '_') + "-" + endpoint + "-" + safeQuery + ".html";
            Path path = Path.of("logs", fileName);
            Files.writeString(path, html, StandardCharsets.UTF_8);
            log.info("[WebImageSearch] 已保存调试 HTML: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("[WebImageSearch] 保存调试 HTML 失败: {}", e.getMessage());
        }
    }

    // ── URL 导入图库 ─────────────────────────────────────────────────

    @Tool(name = "importImage",
          description = """
                  Import an image from a URL directly into the gallery with metadata. \
                  Use when the user provides a direct image link and wants to save it \
                  with a specific name, category, or tags. Supports optional metadata.""")
    public String importImage(
            @ToolParam(required = true,
                       description = "The full URL of the image to import")
            String url,
            @ToolParam(required = false,
                       description = "Display name for the image")
            String name,
            @ToolParam(required = false,
                       description = "Category, e.g. 'reference', 'inspiration'")
            String category,
            @ToolParam(required = false,
                       description = "Comma-separated tags, e.g. 'landscape,winter,snow'")
            String tags,
            ToolContext toolContext) {

        progressContext.start(toolContext, "importImage", "正在导入网络图片：" + url);
        String turnId = currentTurnId(toolContext);
        Map<String, Object> input = Map.of("url", url, "name", name != null ? name : "", "category", category != null ? category : "");
        taskLedger.beforeCall(turnId, "importImage", input);
        try {
            urlValidator.validate(url);
            List<String> tagList = (tags != null && !tags.isBlank())
                    ? List.of(tags.split("\\s*,\\s*"))
                    : List.of();
            String picName = (name != null && !name.isBlank()) ? name : "imported-image";
            GalleryImportUrlRequest req = new GalleryImportUrlRequest(
                    url, picName, null, category, tagList);
            GalleryPicture saved = galleryService.importUrl(req);

            log.info("[WebSearch] 图片URL导入 pictureId={} name={}", saved.id(), saved.name());
            progressContext.done(toolContext, "importImage", "图片已导入图库 [ID:" + saved.id() + "] " + saved.name());
            Map<String, Object> output = Map.of("pictureId", saved.id(), "pictureName", saved.name());
            taskLedger.recordSuccess(turnId, "importImage", input, output, ToolExecutionRecord.GALLERY_CREATED);
            return "图片已从 URL 导入图库 [ID:" + saved.id() + "] " + saved.name();
        } catch (BusinessException e) {
            progressContext.fail(toolContext, "importImage", e.getMessage());
            taskLedger.recordFailure(turnId, "importImage", input, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[WebSearch] URL导入失败 url={}", url, e);
            progressContext.fail(toolContext, "importImage", e.getMessage());
            taskLedger.recordFailure(turnId, "importImage", input, e.getMessage());
            return "图片导入失败：" + e.getMessage();
        }
    }

    // ── HTTP helpers ─────────────────────────────────────────────────

    /** Fetch with automatic redirect following — used for search engines. */
    private String fetchSearchString(String url, int maxBytes, boolean useProxy) throws Exception {
        return fetchSearchPage(url, maxBytes, useProxy).body();
    }

    private FetchedSearchPage fetchSearchPage(String url, int maxBytes, boolean useProxy) throws Exception {
        URI uri = urlValidator.validate(url);
        var clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (useProxy && props.hasProxy()) {
            clientBuilder.proxy(ProxySelector.of(
                    new InetSocketAddress(props.proxyHost(), props.proxyPort())));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", "https://" + uri.getHost() + "/")
                .header("User-Agent", props.userAgent())
                .GET()
                .build();
        HttpResponse<InputStream> resp = clientBuilder.build().send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        ThrowUtils.throwIf(status < 200 || status >= 300, ErrorCode.WEB_FETCH_FAILED, "HTTP " + status);
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        try (InputStream is = resp.body()) {
            byte[] bytes = is.readNBytes(maxBytes + 1);
            ThrowUtils.throwIf(bytes.length > maxBytes, ErrorCode.RESOURCE_TOO_LARGE, "网页超过 " + (maxBytes / 1024 / 1024) + "MB");
            return new FetchedSearchPage(uri.toString(), status, contentType, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private String fetchString(String url, int maxBytes) throws Exception {
        URI uri = urlValidator.validate(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("User-Agent", props.userAgent())
                .GET()
                .build();
        return fetchStringWithRedirect(request, 0, maxBytes);
    }

    private String fetchStringWithRedirect(HttpRequest request, int redirects, int maxBytes) throws Exception {
        HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status >= 300 && status < 400) {
            ThrowUtils.throwIf(redirects >= MAX_REDIRECTS, ErrorCode.WEB_FETCH_FAILED, "重定向次数过多");
            String location = resp.headers().firstValue("Location")
                    .orElseThrow(() -> new BusinessException(ErrorCode.WEB_FETCH_FAILED, "重定向地址为空"));
            URI newUri = urlValidator.validate(request.uri().resolve(location).toString());
            HttpRequest newRequest = HttpRequest.newBuilder(newUri)
                    .timeout(request.timeout().orElse(Duration.ofSeconds(props.readTimeoutSeconds())))
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("User-Agent", props.userAgent())
                    .GET()
                    .build();
            return fetchStringWithRedirect(newRequest, redirects + 1, maxBytes);
        }
        ThrowUtils.throwIf(status < 200 || status >= 300, ErrorCode.WEB_FETCH_FAILED, "HTTP " + status);
        try (InputStream is = resp.body()) {
            byte[] bytes = is.readNBytes(maxBytes + 1);
            ThrowUtils.throwIf(bytes.length > maxBytes, ErrorCode.RESOURCE_TOO_LARGE, "网页超过 " + (maxBytes / 1024 / 1024) + "MB");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private byte[] downloadBytes(URI uri, int redirects, int maxBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.readTimeoutSeconds()))
                .header("Accept", "image/*")
                .header("User-Agent", props.userAgent())
                .GET()
                .build();
        HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status >= 300 && status < 400) {
            ThrowUtils.throwIf(redirects >= MAX_REDIRECTS, ErrorCode.WEB_FETCH_FAILED, "重定向次数过多");
            String location = resp.headers().firstValue("Location")
                    .orElseThrow(() -> new BusinessException(ErrorCode.WEB_FETCH_FAILED, "重定向地址为空"));
            URI newUri = urlValidator.validate(uri.resolve(location).toString());
            return downloadBytes(newUri, redirects + 1, maxBytes);
        }
        ThrowUtils.throwIf(status < 200 || status >= 300, ErrorCode.WEB_FETCH_FAILED, "HTTP " + status);
        try (InputStream is = resp.body()) {
            byte[] bytes = is.readNBytes(maxBytes + 1);
            ThrowUtils.throwIf(bytes.length > maxBytes, ErrorCode.RESOURCE_TOO_LARGE, "文件超过 " + (maxBytes / 1024 / 1024) + "MB");
            return bytes;
        }
    }

    private static String detectContentType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return "application/octet-stream";
        int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF, b2 = bytes[2] & 0xFF, b3 = bytes[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "image/jpeg";
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "image/png";
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return "image/gif";
        if (bytes.length >= 12
                && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) {
            return "image/webp";
        }
        if (b0 == 0x42 && b1 == 0x4D) return "image/bmp";
        return "application/octet-stream";
    }

    private static String extractFilename(URI uri, String contentType) {
        String path = uri.getPath();
        if (path != null && path.contains("/") && path.lastIndexOf('/') < path.length() - 1) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.contains(".")) return name;
        }
        String ext = "png";
        if (contentType.startsWith("image/")) ext = contentType.substring(6).toLowerCase(Locale.ROOT);
        return "web-download." + ext;
    }

    private static String normalizeSource(String source) {
        return (source != null && !source.isBlank()) ? source.strip().toLowerCase(Locale.ROOT) : "auto";
    }

    private static String sanitizeName(String name) {
        String cleaned = name.replaceAll("[\\r\\n\\t]", " ").strip();
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80) + "...";
        }
        return cleaned.isBlank() ? "web-download" : cleaned;
    }

    private static String extractJsonString(String json, String key) {
        if (json == null || json.isBlank()) return "";
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) return "";
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) return "";
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return "";
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                value.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                value.append("\\u").append(hex);
                                i += 4;
                            }
                        } else {
                            value.append(c);
                        }
                    }
                    default -> value.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString().strip();
    }

    private static String currentTurnId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        Object value = toolContext.getContext().get("turnId");
        return value instanceof String text ? text : null;
    }

    private record SearchResult(String title, String url, String snippet) {}

    private record FetchedSearchPage(String url, int status, String contentType, String body) {}

    private record RawImageCandidate(String title, String imageUrl, String sourceUrl, String parseSource) {}

    private record NormalizedCandidate(String title, String imageUrl, String sourceUrl, String parseSource) {}

    private record ImageCandidate(String title, String imageUrl, String sourceUrl, String parseSource, double score) {}

    private static class ImageSearchDebugStats {
        private final String query;
        private final String host;
        private final String endpoint;
        private final String url;
        private final int status;
        private final String contentType;
        private final int bytes;
        private final String title;
        private final int iuscSelectors;
        private final int mimgSelectors;
        private final int dgMimgSelectors;
        private int iuscParsed;
        private int asyncParsed;
        private int scopedParsed;
        private int parsed;

        private ImageSearchDebugStats(String query, String host, String endpoint, String url, int status,
                                      String contentType, int bytes, String title, int iuscSelectors,
                                      int mimgSelectors, int dgMimgSelectors) {
            this.query = query;
            this.host = host;
            this.endpoint = endpoint;
            this.url = url;
            this.status = status;
            this.contentType = contentType;
            this.bytes = bytes;
            this.title = title;
            this.iuscSelectors = iuscSelectors;
            this.mimgSelectors = mimgSelectors;
            this.dgMimgSelectors = dgMimgSelectors;
        }

        @Override
        public String toString() {
            return "queryLength=" + (query != null ? query.length() : 0) + " host=" + host
                    + " endpoint=" + endpoint
                    + " status=" + status
                    + " bytes=" + bytes
                    + " contentType=" + contentType
                    + " title=" + sanitizeName(title != null ? title : "")
                    + " selectors{iusc=" + iuscSelectors
                    + ",mimg=" + mimgSelectors
                    + ",dgMimg=" + dgMimgSelectors
                    + "} parsed{iusc=" + iuscParsed
                    + ",async=" + asyncParsed
                    + ",scoped=" + scopedParsed
                    + ",total=" + parsed
                    + "} urlHost=" + hostOf(url);
        }
    }
}
