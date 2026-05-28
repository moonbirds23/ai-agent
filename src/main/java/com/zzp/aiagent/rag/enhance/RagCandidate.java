package com.zzp.aiagent.rag.enhance;

import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.model.PictureAiProfile;

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
