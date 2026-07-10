package com.zzp.imageretrievalmcp.contract;

import java.util.List;

/**
 * Request DTO for gallery vector/semantic search.
 * Uses compact constructor validation — defaults for optional fields,
 * validation for required fields.
 */
public record GallerySearchRequest(
    String query,
    String category,
    List<String> tags,
    List<String> styleHints,
    List<String> colorHints,
    List<String> compositionHints,
    boolean favoritedOnly,
    String referenceMode,
    int candidateSize,
    double minVectorScore
) {
    public GallerySearchRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required and must not be blank");
        }
        if (query.length() > 500) {
            throw new IllegalArgumentException("query must not exceed 500 characters");
        }
        if (candidateSize < 1 || candidateSize > 50) {
            candidateSize = 20;
        }
        if (minVectorScore < 0 || minVectorScore > 1) {
            minVectorScore = 0.4;
        }
        if (tags == null) {
            tags = List.of();
        }
        if (styleHints == null) {
            styleHints = List.of();
        }
        if (colorHints == null) {
            colorHints = List.of();
        }
        if (compositionHints == null) {
            compositionHints = List.of();
        }
        if (referenceMode == null || referenceMode.isBlank()) {
            referenceMode = "comprehensive";
        }
    }
}
