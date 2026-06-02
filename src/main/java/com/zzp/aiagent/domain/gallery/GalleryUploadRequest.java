package com.zzp.aiagent.domain.gallery;

import java.util.List;

public record GalleryUploadRequest(
        String imageBase64,
        String name,
        String introduction,
        String category,
        List<String> tags,
        Boolean favorited,
        String storageLocation
) {}
