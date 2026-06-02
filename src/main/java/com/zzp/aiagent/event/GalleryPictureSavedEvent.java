package com.zzp.aiagent.event;

import com.zzp.aiagent.model.entity.GalleryPicture;

public record GalleryPictureSavedEvent(GalleryPicture picture, byte[] imageBytes, String contentType, String base64Data) {}
