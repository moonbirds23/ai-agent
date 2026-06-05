package com.zzp.aiagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "网络图片候选")
public record ImageCandidateVO(
        @Schema(description = "图片标题") String title,
        @Schema(description = "图片直链") String imageUrl,
        @Schema(description = "来源页面 URL") String sourceUrl,
        @Schema(description = "解析来源") String parseSource,
        @Schema(description = "相关性评分") double score
) {}
