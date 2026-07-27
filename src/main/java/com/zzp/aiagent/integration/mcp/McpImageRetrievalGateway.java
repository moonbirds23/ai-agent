package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP proxy implementation of {@link ImageRetrievalGateway}.
 * Routes all image retrieval calls through the MCP server on localhost:8232.
 * Activated when {@code IMAGE_RETRIEVAL_MODE=mcp}.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "app.integrations.image-retrieval",
        name = "mode",
        havingValue = "mcp")
@Slf4j
public class McpImageRetrievalGateway implements ImageRetrievalGateway {

    private final McpToolInvoker mcpToolInvoker;
    private final ObjectMapper objectMapper;

    public McpImageRetrievalGateway(McpToolInvoker mcpToolInvoker, ObjectMapper objectMapper) {
        this.mcpToolInvoker = mcpToolInvoker;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    @Retry(name = "image-retrieval-mcp")
    @CircuitBreaker(name = "image-retrieval-mcp")
    public List<Map<String, Object>> searchPexels(String query, int perPage, int page) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        args.put("perPage", perPage);
        args.put("page", page);

        String json = mcpToolInvoker.callTool(McpToolNames.PEXELS_SEARCH, args);
        return extractPhotos(json);
    }

    @Override
    @SuppressWarnings("unchecked")
    @Retry(name = "image-retrieval-mcp")
    @CircuitBreaker(name = "image-retrieval-mcp")
    public List<Map<String, Object>> curatedPexels(int perPage, int page) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("perPage", perPage);
        args.put("page", page);

        String json = mcpToolInvoker.callTool(McpToolNames.PEXELS_CURATED, args);
        return extractPhotos(json);
    }

    @Override
    @SuppressWarnings("unchecked")
    @Retry(name = "image-retrieval-mcp")
    @CircuitBreaker(name = "image-retrieval-mcp")
    public Map<String, Object> getPexelsPhoto(int photoId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("photoId", photoId);

        String json = unwrapJsonString(mcpToolInvoker.callTool(McpToolNames.PEXELS_GET_PHOTO, args));
        // getPhoto returns a single PexelsPhotoDTO, not wrapped in a search response
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[McpGateway] Failed to parse getPexelsPhoto response, returning empty map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Extract the photos list from an MCP response JSON string.
     * The MCP server returns a {@code PexelsSearchResponse} with a {@code photos} array.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractPhotos(String json) {
        try {
            json = unwrapJsonString(json);
            Map<String, Object> response = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object photosObj = response.get("photos");
            if (photosObj instanceof List<?> list) {
                return list.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }
            log.warn("[McpGateway] Response missing 'photos' array, responseLength={}",
                    json != null ? json.length() : 0);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[McpGateway] Failed to parse MCP response JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Spring AI MCP serializes a Java {@code String} tool result as a JSON
     * string literal. Unwrap that one transport layer before parsing the
     * application JSON object.
     */
    String unwrapJsonString(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isTextual() ? node.textValue() : value;
        } catch (Exception ignored) {
            return value;
        }
    }
}
