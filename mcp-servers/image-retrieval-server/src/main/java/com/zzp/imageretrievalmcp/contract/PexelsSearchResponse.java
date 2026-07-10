package com.zzp.imageretrievalmcp.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response DTO for Pexels photo search results.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PexelsSearchResponse(
    String schemaVersion,
    String requestId,
    String source,
    String query,
    long latencyMs,
    int totalResults,
    int page,
    int perPage,
    List<PexelsPhotoDTO> photos
) {
    public PexelsSearchResponse {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = "1.0";
        }
        if (source == null || source.isBlank()) {
            source = "pexels";
        }
    }
}
