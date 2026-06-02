package com.zzp.aiagent.service;

import com.zzp.aiagent.model.dto.image.DownloadedImage;

public interface ImageDownloadService {

    DownloadedImage download(String imageUrl);
}
