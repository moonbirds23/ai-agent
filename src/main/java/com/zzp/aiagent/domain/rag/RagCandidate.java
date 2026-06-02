package com.zzp.aiagent.domain.rag;

import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.entity.PictureAiProfile;

import java.util.Collections;
import java.util.List;

public record RagCandidate(
        GalleryPicture picture,
        PictureAiProfile profile,
        double vectorScore,
        double keywordScore,
        double metadataScore,
        double finalScore,
        List<String> reasons
) {
    public static RagCandidate of(GalleryPicture picture, PictureAiProfile profile, double vectorScore) {
        return new RagCandidate(picture, profile, vectorScore, 0, 0, vectorScore, Collections.emptyList());
    }
}
