package com.zzp.aiagent.domain.pexels;

/**
 * Pexels photo search service.
 * <p>
 * This interface is intentionally kept framework-agnostic so it can later be
 * exposed as an MCP server (by swapping the implementation or wiring the same
 * impl behind {@code spring-ai-starter-mcp-server-webmvc}).
 */
public interface PexelsPhotoService {

    /**
     * Search Pexels photos.
     *
     * @param request search parameters
     * @return search result with photos and pagination info
     */
    PexelsSearchResult search(PexelsSearchRequest request);

    /**
     * Browse curated photos (no search query needed).
     *
     * @param perPage results per page
     * @param page    page number
     * @return curated photos
     */
    PexelsSearchResult curated(int perPage, int page);

    /**
     * Get full metadata for a single photo.
     *
     * @param photoId Pexels photo ID
     * @return photo with all metadata
     */
    PexelsPhoto getPhoto(long photoId);

    /**
     * Download original photo bytes.
     *
     * @param imageUrl the image URL (from {@link PexelsPhotoSrc#original()} or a fallback)
     * @return raw image bytes
     */
    byte[] downloadPhoto(String imageUrl);
}
