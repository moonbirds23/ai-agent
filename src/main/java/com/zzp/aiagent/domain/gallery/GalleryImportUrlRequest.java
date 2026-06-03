package com.zzp.aiagent.domain.gallery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record GalleryImportUrlRequest(
        @NotBlank @Size(max = 2048) String imageUrl,
        @NotBlank @Size(max = 200) String name,
        String introduction,
        String category,
        List<String> tags
) {}
