package com.zzp.imageretrievalmcp.pexels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.imageretrievalmcp.config.PexelsConfig;
import com.zzp.imageretrievalmcp.contract.PexelsPhotoDTO;
import com.zzp.imageretrievalmcp.contract.PexelsSearchRequest;
import com.zzp.imageretrievalmcp.contract.PexelsSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pexels API integration using Spring 6 {@link RestClient}.
 * Handles search, curated listing, and single-photo retrieval.
 */
@Service
public class PexelsPhotoServiceImpl implements PexelsPhotoService {

    private static final Logger log = LoggerFactory.getLogger(PexelsPhotoServiceImpl.class);

    private final RestClient restClient;
    private final PexelsConfig config;
    private final ObjectMapper objectMapper;

    public PexelsPhotoServiceImpl(PexelsConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(config.readTimeoutSeconds()));

        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", config.apiKey())
                .build();
    }

    @Override
    public PexelsSearchResponse searchPhotos(PexelsSearchRequest request) {
        long startMs = System.currentTimeMillis();

        int perPage = Math.min(request.perPage(), config.searchMaxResults());
        String encodedQuery = UriUtils.encodeQueryParam(request.query(), StandardCharsets.UTF_8);
        String path = "/v1/search?query={query}&per_page={perPage}&page={page}";

        String rawJson = restClient.get()
                .uri(path, encodedQuery, perPage, request.page())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(this::isAuthError, (req, resp) -> {
                    throw new PexelsAuthException("Pexels API authentication failed. Verify your API key.");
                })
                .onStatus(this::isRateLimit, (req, resp) -> {
                    throw new PexelsRateLimitException("Pexels API rate limit exceeded. Retry after " +
                            resp.getHeaders().getFirst("Retry-After") + " seconds.");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    throw new RuntimeException("Pexels API server error: HTTP " + resp.getStatusCode().value());
                })
                .body(String.class);

        long latencyMs = System.currentTimeMillis() - startMs;
        return parseSearchResponse(rawJson, request.query(), request.page(), perPage, latencyMs);
    }

    @Override
    public PexelsSearchResponse curatedPhotos(int perPage, int page) {
        long startMs = System.currentTimeMillis();

        perPage = Math.min(perPage, config.searchMaxResults());
        String path = "/v1/curated?per_page={perPage}&page={page}";

        String rawJson = restClient.get()
                .uri(path, perPage, page)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(this::isAuthError, (req, resp) -> {
                    throw new PexelsAuthException("Pexels API authentication failed. Verify your API key.");
                })
                .onStatus(this::isRateLimit, (req, resp) -> {
                    throw new PexelsRateLimitException("Pexels API rate limit exceeded. Retry after " +
                            resp.getHeaders().getFirst("Retry-After") + " seconds.");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    throw new RuntimeException("Pexels API server error: HTTP " + resp.getStatusCode().value());
                })
                .body(String.class);

        long latencyMs = System.currentTimeMillis() - startMs;
        return parseSearchResponse(rawJson, null, page, perPage, latencyMs);
    }

    @Override
    public PexelsPhotoDTO getPhoto(int photoId) {
        long startMs = System.currentTimeMillis();

        String path = "/v1/photos/{id}";

        String rawJson = restClient.get()
                .uri(path, photoId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(this::isAuthError, (req, resp) -> {
                    throw new PexelsAuthException("Pexels API authentication failed. Verify your API key.");
                })
                .onStatus(this::isRateLimit, (req, resp) -> {
                    throw new PexelsRateLimitException("Pexels API rate limit exceeded. Retry after " +
                            resp.getHeaders().getFirst("Retry-After") + " seconds.");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    throw new RuntimeException("Pexels API server error: HTTP " + resp.getStatusCode().value());
                })
                .body(String.class);

        try {
            return objectMapper.readValue(rawJson, PexelsPhotoDTO.class);
        } catch (Exception e) {
            log.error("Failed to parse Pexels photo response for ID {}", photoId, e);
            throw new RuntimeException("Failed to parse Pexels photo response", e);
        }
    }

    /**
     * Parse the raw JSON search/curated response into our contract DTO.
     */
    private PexelsSearchResponse parseSearchResponse(String rawJson, String query, int page, int perPage, long latencyMs) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);

            int totalResults = root.hasNonNull("total_results") ? root.get("total_results").asInt() : 0;

            List<PexelsPhotoDTO> photos = new ArrayList<>();
            JsonNode photosNode = root.get("photos");
            if (photosNode != null && photosNode.isArray()) {
                for (JsonNode photoNode : photosNode) {
                    photos.add(parsePhotoNode(photoNode));
                }
            }

            return new PexelsSearchResponse(
                    "1.0",
                    UUID.randomUUID().toString(),
                    "pexels",
                    query,
                    latencyMs,
                    totalResults,
                    page,
                    perPage,
                    photos
            );
        } catch (Exception e) {
            log.error("Failed to parse Pexels search response", e);
            throw new RuntimeException("Failed to parse Pexels search response", e);
        }
    }

    /**
     * Parse a single photo JSON node into a PexelsPhotoDTO.
     * Gracefully handles missing fields by falling back to defaults.
     */
    private PexelsPhotoDTO parsePhotoNode(JsonNode node) {
        long id = node.hasNonNull("id") ? node.get("id").asLong() : 0L;
        int width = node.hasNonNull("width") ? node.get("width").asInt() : 0;
        int height = node.hasNonNull("height") ? node.get("height").asInt() : 0;
        String alt = node.hasNonNull("alt") ? node.get("alt").asText() : "";
        String photographer = node.hasNonNull("photographer") ? node.get("photographer").asText() : "Unknown";
        String photographerUrl = node.hasNonNull("photographer_url") ? node.get("photographer_url").asText() : "";
        String url = node.hasNonNull("url") ? node.get("url").asText() : "";
        String avgColor = node.hasNonNull("avg_color") ? node.get("avg_color").asText() : "";

        PexelsPhotoDTO.PhotoSrc src = parsePhotoSrc(node.get("src"));

        return new PexelsPhotoDTO(id, width, height, alt, photographer, photographerUrl, url, avgColor, src);
    }

    /**
     * Parse the nested "src" object from a photo JSON node.
     */
    private PexelsPhotoDTO.PhotoSrc parsePhotoSrc(JsonNode srcNode) {
        if (srcNode == null || srcNode.isNull()) {
            return new PexelsPhotoDTO.PhotoSrc("", "", "", "", "", "", "", "");
        }
        return new PexelsPhotoDTO.PhotoSrc(
                textOrEmpty(srcNode, "original"),
                textOrEmpty(srcNode, "large2x"),
                textOrEmpty(srcNode, "large"),
                textOrEmpty(srcNode, "medium"),
                textOrEmpty(srcNode, "small"),
                textOrEmpty(srcNode, "portrait"),
                textOrEmpty(srcNode, "landscape"),
                textOrEmpty(srcNode, "tiny")
        );
    }

    private String textOrEmpty(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    private boolean isAuthError(HttpStatusCode status) {
        int code = status.value();
        return code == 401 || code == 403;
    }

    private boolean isRateLimit(HttpStatusCode status) {
        return status.value() == 429;
    }
}
