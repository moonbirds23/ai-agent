package com.zzp.aiagent.gallery.model;

import java.util.List;

public record GalleryQueryRequest(
        Integer page,
        Integer pageSize,
        String keyword,
        String category,
        List<String> tags,
        Boolean favoritedOnly,
        String sourceType
) {}
