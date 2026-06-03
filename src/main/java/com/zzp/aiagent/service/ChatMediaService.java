package com.zzp.aiagent.service;

import com.zzp.aiagent.model.entity.GalleryPicture;
import org.springframework.ai.content.Media;

public interface ChatMediaService {

    /**
     * Create a Media from gallery picture, base64 image, or image URL (tried in that order).
     * Returns null if no image source is available or all attempts fail.
     */
    Media createMedia(GalleryPicture savedPicture, String imageBase64, String imageUrl);

    /** Map a picture format extension (png, jpg, webp, etc.) to a MIME type string. */
    String mimeTypeFromFormat(String picFormat);
}
