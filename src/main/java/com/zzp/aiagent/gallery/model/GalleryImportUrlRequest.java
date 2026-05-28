package com.zzp.aiagent.gallery.model;

import java.util.List;

public record GalleryImportUrlRequest(
        String imageUrl,
        String name,
        String introduction,
        String category,
        List<String> tags
) {}
