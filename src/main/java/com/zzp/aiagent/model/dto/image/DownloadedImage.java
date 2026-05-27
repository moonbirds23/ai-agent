package com.zzp.aiagent.model.dto.image;

public record DownloadedImage(
        byte[] bytes,
        String contentType,
        String filename
) {}
