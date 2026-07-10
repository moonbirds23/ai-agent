package com.zzp.aiagent.integration.mcp;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over image retrieval backends: local (direct Pexels HTTP) or MCP proxy.
 * Each method returns structured data that the caller uses to build tool responses.
 */
public interface ImageRetrievalGateway {

    /**
     * Search Pexels photos.
     *
     * @param query   search keywords
     * @param perPage results per page
     * @param page    page number
     * @return list of photo data maps, each with keys: id, width, height, alt, photographer,
     *         photographerUrl, url, avgColor, src (map with original/large2x/large/medium/small/portrait/landscape/tiny)
     */
    List<Map<String, Object>> searchPexels(String query, int perPage, int page);

    /**
     * Browse Pexels curated/featured photos.
     *
     * @param perPage results per page
     * @param page    page number
     * @return list of photo data maps (same shape as search)
     */
    List<Map<String, Object>> curatedPexels(int perPage, int page);

    /**
     * Get full metadata for a single Pexels photo.
     *
     * @param photoId Pexels photo ID
     * @return photo data map
     */
    Map<String, Object> getPexelsPhoto(int photoId);
}
