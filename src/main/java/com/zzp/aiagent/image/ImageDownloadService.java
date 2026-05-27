package com.zzp.aiagent.image;

import com.zzp.aiagent.model.dto.image.DownloadedImage;

public interface ImageDownloadService {

    DownloadedImage download(String imageUrl);
}
