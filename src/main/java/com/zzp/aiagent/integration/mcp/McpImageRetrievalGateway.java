package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
    public List<Map<String, Object>> curatedPexels(int perPage, int page) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("perPage", perPage);
        args.put("page", page);

        String json = mcpToolInvoker.callTool(McpToolNames.PEXELS_CURATED, args);
        return extractPhotos(json);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPexelsPhoto(int photoId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("photoId", photoId);

        String json = mcpToolInvoker.callTool(McpToolNames.PEXELS_GET_PHOTO, args);
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
            Map<String, Object> response = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object photosObj = response.get("photos");
            if (photosObj instanceof List<?> list) {
                return list.stream()
                        .filter(item -> item instanceof Map)
                        .map(item -> (Map<String, Object>) item)
                        .toList();
            }
            log.warn("[McpGateway] Response missing 'photos' array: {}", json);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[McpGateway] Failed to parse MCP response JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
