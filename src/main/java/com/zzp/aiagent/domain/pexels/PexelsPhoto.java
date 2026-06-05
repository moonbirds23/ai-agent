package com.zzp.aiagent.domain.pexels;

/**
 * A single Pexels photo with full metadata.
 */
public record PexelsPhoto(
        long id,
        int width,
        int height,
        String url,               // Pexels page URL
        String photographer,       // Photographer name
        String photographerUrl,    // Photographer's Pexels profile URL
        long photographerId,
        String avgColor,           // #HEX, e.g. "#140E15"
        String alt,                // Alt text / description
        PexelsPhotoSrc src         // 7 size variants
) {}
