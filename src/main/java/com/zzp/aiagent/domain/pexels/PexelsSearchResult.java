package com.zzp.aiagent.domain.pexels;

import java.util.List;

/**
 * Response from {@code GET /v1/search} or {@code GET /v1/curated}.
 *
 * @param totalResults Total number of matching results
 * @param nextPage     URL for the next page (null if last page)
 * @param photos       Search result photos
 */
public record PexelsSearchResult(
        int page,
        int perPage,
        int totalResults,
        String url,              // Pexels web search URL
        String nextPage,         // Next page API URL, null if last
        List<PexelsPhoto> photos
) {}
