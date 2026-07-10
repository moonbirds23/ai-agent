package com.zzp.aiagent.integration.mcp;

import com.zzp.aiagent.domain.pexels.PexelsPhoto;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import com.zzp.aiagent.domain.pexels.PexelsPhotoSrc;
import com.zzp.aiagent.domain.pexels.PexelsSearchRequest;
import com.zzp.aiagent.domain.pexels.PexelsSearchResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local (direct Pexels HTTP) implementation of {@link ImageRetrievalGateway}.
 * Delegates to the existing {@link PexelsPhotoService} for all operations.
 * Activated when {@code IMAGE_RETRIEVAL_MODE} is not set to {@code mcp}.
 */
@Component
@Profile("!test")
public class LocalImageRetrievalGateway implements ImageRetrievalGateway {

    private final PexelsPhotoService pexelsPhotoService;

    public LocalImageRetrievalGateway(PexelsPhotoService pexelsPhotoService) {
        this.pexelsPhotoService = pexelsPhotoService;
    }

    @Override
    public List<Map<String, Object>> searchPexels(String query, int perPage, int page) {
        PexelsSearchRequest request = new PexelsSearchRequest(query, perPage, page, null, null, null, "zh-CN");
        PexelsSearchResult result = pexelsPhotoService.search(request);
        return result.photos().stream().map(LocalImageRetrievalGateway::toMap).toList();
    }

    @Override
    public List<Map<String, Object>> curatedPexels(int perPage, int page) {
        PexelsSearchResult result = pexelsPhotoService.curated(perPage, page);
        return result.photos().stream().map(LocalImageRetrievalGateway::toMap).toList();
    }

    @Override
    public Map<String, Object> getPexelsPhoto(int photoId) {
        PexelsPhoto photo = pexelsPhotoService.getPhoto(photoId);
        return toMap(photo);
    }

    /**
     * Convert a domain {@link PexelsPhoto} record to a map suitable for tool response construction.
     */
    static Map<String, Object> toMap(PexelsPhoto photo) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", photo.id());
        map.put("width", photo.width());
        map.put("height", photo.height());
        map.put("alt", photo.alt());
        map.put("photographer", photo.photographer());
        map.put("photographerUrl", photo.photographerUrl());
        map.put("url", photo.url());
        map.put("avgColor", photo.avgColor());
        if (photo.src() != null) {
            map.put("src", srcToMap(photo.src()));
        }
        return map;
    }

    private static Map<String, Object> srcToMap(PexelsPhotoSrc src) {
        Map<String, Object> smap = new LinkedHashMap<>();
        putNonNull(smap, "original", src.original());
        putNonNull(smap, "large2x", src.large2x());
        putNonNull(smap, "large", src.large());
        putNonNull(smap, "medium", src.medium());
        putNonNull(smap, "small", src.small());
        putNonNull(smap, "portrait", src.portrait());
        putNonNull(smap, "landscape", src.landscape());
        putNonNull(smap, "tiny", src.tiny());
        return smap;
    }

    private static void putNonNull(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
