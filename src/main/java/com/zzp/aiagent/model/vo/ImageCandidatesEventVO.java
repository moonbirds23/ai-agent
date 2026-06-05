package com.zzp.aiagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "图片候选 SSE 事件数据")
public record ImageCandidatesEventVO(
        @Schema(description = "搜索词") String query,
        @Schema(description = "搜索来源") String source,
        @Schema(description = "图片候选列表") List<ImageCandidateVO> candidates
) {}
