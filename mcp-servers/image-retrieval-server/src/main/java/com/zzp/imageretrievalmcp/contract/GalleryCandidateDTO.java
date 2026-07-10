package com.zzp.imageretrievalmcp.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A single gallery picture candidate returned by the search.
 * Contains metadata, profile, and relevance scores.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GalleryCandidateDTO(
    long pictureId,
    String picHash,
    String name,
    String intro,
    String category,
    List<String> tags,
    boolean favorited,
    Integer width,
    Integer height,
    String format,
    String url,
    String thumbnailUrl,
    GalleryProfileDTO profile,
    CandidateScores scores
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidateScores(
        double vectorScore,
        double keywordScore,
        double metadataScore,
        double combinedScore
    ) {}
}
