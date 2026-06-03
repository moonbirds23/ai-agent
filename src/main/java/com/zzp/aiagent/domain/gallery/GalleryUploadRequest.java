package com.zzp.aiagent.domain.gallery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record GalleryUploadRequest(
        @NotBlank String imageBase64,
        @NotBlank @Size(max = 200) String name,
        String introduction,
        String category,
        List<String> tags,
        Boolean favorited,
        String storageLocation
) {}
