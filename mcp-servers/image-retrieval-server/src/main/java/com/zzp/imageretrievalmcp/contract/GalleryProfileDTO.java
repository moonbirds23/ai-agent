package com.zzp.imageretrievalmcp.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI-generated profile metadata for a gallery picture.
 * Includes visual analysis results: subject, scene, style, colors, composition, lighting, mood.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GalleryProfileDTO(
    String subject,
    String scene,
    String style,
    String colors,
    String composition,
    String lighting,
    String mood,
    String imagePrompt
) {}
