package com.zzp.imageretrievalmcp.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response DTO for gallery search results.
 * Includes schema versioning, request correlation, and candidate list with metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GallerySearchResponse(
    String schemaVersion,
    String requestId,
    String query,
    String rewrittenQuery,
    String retrievalStrategy,
    int totalCandidates,
    long latencyMs,
    List<GalleryCandidateDTO> candidates,
    String debugTrace
) {
    public GallerySearchResponse {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = "1.0";
        }
    }
}
