package com.zzp.imageretrievalmcp.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single Pexels photo with all standard size variants.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PexelsPhotoDTO(
    long id,
    int width,
    int height,
    String alt,
    String photographer,
    @JsonProperty("photographer_url") String photographerUrl,
    String url,
    @JsonProperty("avg_color") String avgColor,
    PhotoSrc src
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoSrc(
        String original,
        String large2x,
        String large,
        String medium,
        String small,
        String portrait,
        String landscape,
        String tiny
    ) {}
}
