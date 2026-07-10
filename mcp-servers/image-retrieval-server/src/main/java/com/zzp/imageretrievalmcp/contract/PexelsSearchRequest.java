package com.zzp.imageretrievalmcp.contract;

/**
 * Request DTO for Pexels photo search.
 */
public record PexelsSearchRequest(
    String query,
    int perPage,
    int page
) {
    public PexelsSearchRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required and must not be blank");
        }
        if (perPage < 1 || perPage > 80) {
            perPage = 15;
        }
        if (page < 1) {
            page = 1;
        }
    }
}
