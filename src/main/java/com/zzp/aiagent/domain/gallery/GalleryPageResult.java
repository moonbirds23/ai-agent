package com.zzp.aiagent.domain.gallery;

import com.zzp.aiagent.model.entity.GalleryPicture;

import java.util.List;

public record GalleryPageResult(
        List<GalleryPicture> records,
        long total,
        int page,
        int pageSize
) {}
