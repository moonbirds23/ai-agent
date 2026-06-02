package com.zzp.aiagent.domain.gallery;

import java.util.List;

public record GalleryImportUrlRequest(
        String imageUrl,
        String name,
        String introduction,
        String category,
        List<String> tags
) {}
