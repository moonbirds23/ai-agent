package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.common.UrlSecurityValidator;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.service.ChatMediaService;
import com.zzp.aiagent.service.GalleryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.Locale;

@Service
@Profile("!test")
@Slf4j
public class ChatMediaServiceImpl implements ChatMediaService {

    private final GalleryService galleryService;
    private final UrlSecurityValidator urlValidator;

    public ChatMediaServiceImpl(GalleryService galleryService, UrlSecurityValidator urlValidator) {
        this.galleryService = galleryService;
        this.urlValidator = urlValidator;
    }

    @Override
    public Media createMedia(GalleryPicture savedPicture, String imageBase64, String imageUrl) {
        // 1. Try gallery picture first
        if (savedPicture != null && savedPicture.url() != null) {
            try {
                String mime = mimeTypeFromFormat(savedPicture.picFormat());
                return new Media(MimeTypeUtils.parseMimeType(mime),
                        new URL(savedPicture.url()).toURI());
            } catch (Exception e) {
                log.warn("[ChatMediaService] 图库URL构造失败，降级发送: {}", e.getMessage());
            }
        }

        // 2. Try base64
        if (imageBase64 != null && !imageBase64.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(stripDataUrlPrefix(imageBase64));
            String ext = extractFormatFromBase64(imageBase64);
            String mime = mimeTypeFromFormat(ext);
            return new Media(MimeTypeUtils.parseMimeType(mime), new ByteArrayResource(bytes));
        }

        // 3. Try image URL (validated against SSRF)
        if (imageUrl != null && !imageUrl.isBlank()) {
            try {
                URI validatedUri = urlValidator.validate(imageUrl);
                String ext = extractFormatFromUrl(imageUrl);
                String mime = mimeTypeFromFormat(ext);
                return new Media(MimeTypeUtils.parseMimeType(mime), validatedUri);
            } catch (Exception e) {
                log.warn("[ChatMediaService] 无效图片URL: {}", imageUrl, e);
            }
        }

        return null;
    }

    @Override
    public String mimeTypeFromFormat(String picFormat) {
        if (picFormat == null) return "image/png";
        return switch (picFormat.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    // ── private helpers ───────────────────────────────────────────────

    private String stripDataUrlPrefix(String imageBase64) {
        String trimmed = imageBase64.trim();
        int comma = trimmed.indexOf(',');
        if (trimmed.startsWith("data:image/") && comma >= 0) {
            return trimmed.substring(comma + 1);
        }
        return trimmed;
    }

    /**
     * Extract image format extension from base64 Data URL prefix.
     * e.g. "data:image/png;base64,xxx" returns "png". Defaults to "png" if unrecognized.
     */
    private static String extractFormatFromBase64(String base64) {
        if (base64 == null) return "png";
        String lower = base64.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:image/")) {
            int slash = lower.indexOf('/');
            int semicolon = lower.indexOf(';');
            if (slash >= 0 && semicolon > slash) {
                return lower.substring(slash + 1, semicolon);
            }
        }
        return "png";
    }

    /**
     * Infer image format extension from URL path. Defaults to "png" if unrecognized.
     */
    private static String extractFormatFromUrl(String url) {
        if (url == null) return "png";
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg";
        if (lower.endsWith(".webp")) return "webp";
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".bmp")) return "bmp";
        if (lower.endsWith(".png")) return "png";
        return "png";
    }
}
