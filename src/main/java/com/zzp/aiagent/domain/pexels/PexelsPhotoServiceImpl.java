package com.zzp.aiagent.domain.pexels;

import com.zzp.aiagent.common.UrlSecurityValidator;
import com.zzp.aiagent.domain.web.WebProperties;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.common.ThrowUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pexels API client using {@link java.net.http.HttpClient}.
 * <p>
 * API docs: <a href="https://www.pexels.com/api/documentation/">Pexels API</a>
 */
@Service
@Profile("!test")
@Slf4j
public class PexelsPhotoServiceImpl implements PexelsPhotoService {

    private static final String BASE_URL = "https://api.pexels.com/v1";
    private static final Pattern RATE_LIMIT_PATTERN =
            Pattern.compile("(\\d+)\\s*requests?\\s*(?:per|/|this)\\s*(month|hour|day|minute)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final PexelsProperties props;
    private final UrlSecurityValidator urlValidator;

    public PexelsPhotoServiceImpl(PexelsProperties props, WebProperties webProps,
                                  UrlSecurityValidator urlValidator) {
        this.props = props;
        this.urlValidator = urlValidator;
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds() > 0
                        ? props.connectTimeoutSeconds() : 10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (webProps.hasProxy()) {
            builder.proxy(ProxySelector.of(
                    new InetSocketAddress(webProps.proxyHost(), webProps.proxyPort())));
        }
        this.httpClient = builder.build();
    }

    // ── search ────────────────────────────────────────────────────────

    @Override
    public PexelsSearchResult search(PexelsSearchRequest request) {
        ThrowUtils.throwIf(request.query() == null || request.query().isBlank(),
                ErrorCode.PARAMS_ERROR, "Pexels search query is required");

        StringBuilder url = new StringBuilder(BASE_URL + "/search?");
        appendParam(url, "query", request.query());
        appendParam(url, "per_page", request.perPage());
        appendParam(url, "page", request.page());
        appendParam(url, "orientation", request.orientation());
        appendParam(url, "size", request.size());
        appendParam(url, "color", request.color());
        appendParam(url, "locale", defaultIfNull(request.locale(), props.defaultLocale()));

        String responseJson = get(url.toString());
        return parseSearchResult(responseJson);
    }

    // ── curated ───────────────────────────────────────────────────────

    @Override
    public PexelsSearchResult curated(int perPage, int page) {
        StringBuilder url = new StringBuilder(BASE_URL + "/curated?");
        appendParam(url, "per_page", perPage);
        appendParam(url, "page", page);
        appendParam(url, "locale", props.defaultLocale());

        String responseJson = get(url.toString());
        return parseSearchResult(responseJson);
    }

    // ── getPhoto ──────────────────────────────────────────────────────

    @Override
    public PexelsPhoto getPhoto(long photoId) {
        String url = BASE_URL + "/photos/" + photoId;
        String responseJson = get(url);
        return parsePhoto(responseJson);
    }

    // ── downloadPhoto ─────────────────────────────────────────────────

    @Override
    public byte[] downloadPhoto(String imageUrl) {
        try {
            // SSRF protection — validate URL before connecting
            URI uri = urlValidator.validate(imageUrl);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(props.readTimeoutSeconds() > 0
                            ? props.readTimeoutSeconds() : 30))
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            ThrowUtils.throwIf(status < 200 || status >= 300,
                    ErrorCode.WEB_FETCH_FAILED, "Pexels download HTTP " + status);
            try (InputStream is = resp.body()) {
                return is.readAllBytes();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Pexels] 图片下载失败 url={}", imageUrl, e);
            throw new BusinessException(ErrorCode.WEB_FETCH_FAILED, "Pexels 图片下载失败: " + e.getMessage());
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────

    private String get(String url) {
        try {
            URI uri = URI.create(url);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(props.readTimeoutSeconds() > 0
                            ? props.readTimeoutSeconds() : 30))
                    .header("Authorization", props.apiKey())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            log.debug("[Pexels] GET {}", url);
            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status == 429) {
                log.warn("[Pexels] 触发限流 url={}", url);
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT,
                        "Pexels API 请求过于频繁，请稍后重试");
            }
            if (status < 200 || status >= 300) {
                log.error("[Pexels] HTTP {} url={} body={}", status, url,
                        resp.body().substring(0, Math.min(resp.body().length(), 500)));
                throw new BusinessException(ErrorCode.WEB_FETCH_FAILED,
                        "Pexels API HTTP " + status);
            }

            // Log rate-limit headers
            logRateLimit(resp.headers().map());

            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Pexels] 请求失败 url={}", url, e);
            throw new BusinessException(ErrorCode.WEB_FETCH_FAILED,
                    "Pexels API 请求失败: " + e.getMessage());
        }
    }

    private void logRateLimit(Map<String, List<String>> headers) {
        String limit = firstHeader(headers, "X-Ratelimit-Limit");
        String remaining = firstHeader(headers, "X-Ratelimit-Remaining");
        String reset = firstHeader(headers, "X-Ratelimit-Reset");
        if (limit != null || remaining != null) {
            log.debug("[Pexels] 限流状态 limit={} remaining={} reset={}", limit, remaining, reset);
        }
    }

    // ── JSON parsing (manual, no extra dependency) ────────────────────

    // We use a lightweight manual parser to avoid pulling in Jackson
    // just for API response parsing. The responses are well-structured.

    private PexelsSearchResult parseSearchResult(String json) {
        int perPage = intField(json, "per_page");
        int page = intField(json, "page");
        int totalResults = intField(json, "total_results");
        String url = stringField(json, "url");
        String nextPage = stringField(json, "next_page");
        List<PexelsPhoto> photos = parsePhotoArray(json);
        return new PexelsSearchResult(page, perPage, totalResults, url,
                (nextPage != null && !nextPage.isBlank()) ? nextPage : null, photos);
    }

    private List<PexelsPhoto> parsePhotoArray(String json) {
        List<PexelsPhoto> photos = new ArrayList<>();
        int photosIdx = json.indexOf("\"photos\"");
        if (photosIdx < 0) return photos;
        int bracket = json.indexOf('[', photosIdx);
        if (bracket < 0) return photos;
        int end = findMatchingBracket(json, bracket);
        if (end < 0) return photos;
        String array = json.substring(bracket, end + 1);

        // Split by "id": pattern to find each object
        int pos = 0;
        while (true) {
            int objStart = array.indexOf("{", pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBracket(array, objStart);
            if (objEnd < 0) break;
            String photoJson = array.substring(objStart, objEnd + 1);
            try {
                photos.add(parsePhoto(photoJson));
            } catch (Exception e) {
                log.debug("[Pexels] 跳过解析失败的照片: {}", e.getMessage());
            }
            pos = objEnd + 1;
        }
        return photos;
    }

    PexelsPhoto parsePhoto(String json) {
        long id = longField(json, "id");
        int width = intField(json, "width");
        int height = intField(json, "height");
        String url = stringField(json, "url");
        String photographer = stringField(json, "photographer");
        String photographerUrl = stringField(json, "photographer_url");
        long photographerId = longField(json, "photographer_id");
        String avgColor = stringField(json, "avg_color");
        String alt = stringField(json, "alt");
        PexelsPhotoSrc src = parseSrc(json);
        return new PexelsPhoto(id, width, height, url,
                photographer, photographerUrl, photographerId,
                avgColor, alt, src);
    }

    private PexelsPhotoSrc parseSrc(String json) {
        int srcIdx = json.indexOf("\"src\"");
        if (srcIdx < 0) return emptySrc();
        int brace = json.indexOf('{', srcIdx);
        if (brace < 0) return emptySrc();
        int end = findMatchingBracket(json, brace);
        if (end < 0) return emptySrc();
        String srcJson = json.substring(brace, end + 1);
        return new PexelsPhotoSrc(
                stringField(srcJson, "original"),
                stringField(srcJson, "large2x"),
                stringField(srcJson, "large"),
                stringField(srcJson, "medium"),
                stringField(srcJson, "small"),
                stringField(srcJson, "portrait"),
                stringField(srcJson, "landscape"),
                stringField(srcJson, "tiny")
        );
    }

    private static PexelsPhotoSrc emptySrc() {
        return new PexelsPhotoSrc("", "", "", "", "", "", "", "");
    }

    // ── Lightweight JSON helpers ──────────────────────────────────────

    private static String stringField(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx < 0) return "";
        int colon = json.indexOf(':', keyIdx + needle.length());
        if (colon < 0) return "";
        // Skip whitespace
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) == '"') {
            return unescape(json, i + 1);
        }
        if (json.charAt(i) == 'n' && json.startsWith("null", i)) {
            return "";
        }
        return "";
    }

    private static int intField(String json, String key) {
        String s = stringField(json, key);
        if (s.isEmpty()) {
            // Try number value (not quoted)
            String needle = "\"" + key + "\"";
            int keyIdx = json.indexOf(needle);
            if (keyIdx < 0) return 0;
            int colon = json.indexOf(':', keyIdx + needle.length());
            if (colon < 0) return 0;
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            StringBuilder num = new StringBuilder();
            while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
                num.append(json.charAt(i));
                i++;
            }
            try {
                return Integer.parseInt(num.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longField(String json, String key) {
        String s = stringField(json, key);
        if (s.isEmpty()) {
            // Try number value
            String needle = "\"" + key + "\"";
            int keyIdx = json.indexOf(needle);
            if (keyIdx < 0) return 0;
            int colon = json.indexOf(':', keyIdx + needle.length());
            if (colon < 0) return 0;
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            StringBuilder num = new StringBuilder();
            while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
                num.append(json.charAt(i));
                i++;
            }
            try {
                return Long.parseLong(num.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unescape(String json, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) {
                    char next = json.charAt(i);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 < json.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                    i += 4;
                                } catch (NumberFormatException e) {
                                    sb.append("\\u");
                                }
                            } else {
                                sb.append('\\');
                            }
                        }
                        default -> sb.append(next);
                    }
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    /**
     * Find the matching ']' or '}' starting from an opening bracket position.
     */
    static int findMatchingBracket(String json, int start) {
        char open = json.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped char
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ── Misc helpers ─────────────────────────────────────────────────

    private static void appendParam(StringBuilder sb, String key, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isBlank()) return;
        if (sb.charAt(sb.length() - 1) != '?') sb.append('&');
        sb.append(key).append('=').append(URLEncoder.encode(s, StandardCharsets.UTF_8));
    }

    private static String defaultIfNull(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }
}
