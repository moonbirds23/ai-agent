package com.zzp.aiagent.model.entity;

import java.time.LocalDateTime;

public record PictureAiProfile(
        Long pictureId,
        String subject,
        String scene,
        String style,
        String colors,
        String composition,
        String lighting,
        String mood,
        String imagePrompt,
        String indexText,
        Integer vectorStatus,    // 0=pending, 1=indexed, -1=failed
        LocalDateTime analyzedAt
) {
    public static PictureAiProfile pending(Long pictureId, String subject, String scene,
                                           String style, String colors, String composition,
                                           String lighting, String mood, String imagePrompt,
                                           String indexText) {
        return new PictureAiProfile(pictureId, subject, scene, style, colors,
                composition, lighting, mood, imagePrompt, indexText, 0, null);
    }

    public PictureAiProfile withVectorStatus(Integer vectorStatus) {
        return new PictureAiProfile(pictureId, subject, scene, style, colors,
                composition, lighting, mood, imagePrompt, indexText, vectorStatus, analyzedAt);
    }

    public PictureAiProfile withAnalyzedAt(LocalDateTime analyzedAt) {
        return new PictureAiProfile(pictureId, subject, scene, style, colors,
                composition, lighting, mood, imagePrompt, indexText, vectorStatus, analyzedAt);
    }
}
