package com.zzp.aiagent.domain.pexels;

/**
 * Parameters for {@code GET /v1/search}.
 *
 * @param query       Search query (required)
 * @param perPage     Results per page, 1-80, default 15
 * @param page        Page number, default 1
 * @param orientation landscape / portrait / square
 * @param size        large / medium / small
 * @param color       Named color or hex, e.g. "orange", "#FF6347"
 * @param locale      Locale code, e.g. "zh-CN", "en-US"
 */
public record PexelsSearchRequest(
        String query,
        Integer perPage,
        Integer page,
        String orientation,
        String size,
        String color,
        String locale
) {}
