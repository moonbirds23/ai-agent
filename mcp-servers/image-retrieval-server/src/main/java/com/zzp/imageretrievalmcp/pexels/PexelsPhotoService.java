package com.zzp.imageretrievalmcp.pexels;

import com.zzp.imageretrievalmcp.contract.PexelsPhotoDTO;
import com.zzp.imageretrievalmcp.contract.PexelsSearchRequest;
import com.zzp.imageretrievalmcp.contract.PexelsSearchResponse;

/**
 * Abstraction for Pexels photo API operations.
 */
public interface PexelsPhotoService {

    /**
     * Search photos by query string.
     */
    PexelsSearchResponse searchPhotos(PexelsSearchRequest request);

    /**
     * Retrieve curated (editor-picked) photos.
     */
    PexelsSearchResponse curatedPhotos(int perPage, int page);

    /**
     * Get a single photo by its Pexels ID.
     */
    PexelsPhotoDTO getPhoto(int photoId);
}
