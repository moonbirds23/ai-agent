package com.zzp.aiagent.domain.gallery;

import java.util.List;

/** Request body for updating gallery picture metadata. */
public record GalleryUpdateRequest(
        String name,
        String introduction,
        String category,
        List<String> tags
) {}
