package com.zzp.imageretrievalmcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.imageretrievalmcp.contract.PexelsPhotoDTO;
import com.zzp.imageretrievalmcp.contract.PexelsSearchRequest;
import com.zzp.imageretrievalmcp.contract.PexelsSearchResponse;
import com.zzp.imageretrievalmcp.observability.McpServerTelemetry;
import com.zzp.imageretrievalmcp.pexels.PexelsPhotoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP @Tool-annotated methods that expose Pexels photo search capabilities.
 * Each method delegates to {@link PexelsPhotoService} and returns the result
 * serialized as a JSON string.
 */
@Component
public class PexelsTools {

    private static final Logger log = LoggerFactory.getLogger(PexelsTools.class);

    private final PexelsPhotoService pexelsPhotoService;
    private final ObjectMapper objectMapper;
    private final McpServerTelemetry telemetry;

    public PexelsTools(PexelsPhotoService pexelsPhotoService, ObjectMapper objectMapper,
                       McpServerTelemetry telemetry) {
        this.pexelsPhotoService = pexelsPhotoService;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
    }

    @Tool(description = "搜索 Pexels 高质量图库，返回匹配的图片列表及元数据")
    public String pexelsSearchPhotos(
            @ToolParam(description = "搜索关键词，支持中英文") String query,
            @ToolParam(description = "每页返回数量，默认5，最大80") int perPage,
            @ToolParam(description = "页码，从1开始") int page,
            @ToolParam(required = false, description = "内部链路关联上下文，请勿由模型设置")
            String _agent_traceparent) {
        try (var ignored = telemetry.startToolCall("pexelsSearchPhotos", _agent_traceparent)) {
            PexelsSearchRequest request = new PexelsSearchRequest(query, perPage, page);
            PexelsSearchResponse response = pexelsPhotoService.searchPhotos(request);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("pexelsSearchPhotos failed: queryLength={}", query != null ? query.length() : 0, e);
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    String pexelsSearchPhotos(String query, int perPage, int page) {
        return pexelsSearchPhotos(query, perPage, page, null);
    }

    @Tool(description = "获取 Pexels 精选图片列表")
    public String pexelsCuratedPhotos(
            @ToolParam(description = "每页返回数量，默认5，最大80") int perPage,
            @ToolParam(description = "页码，从1开始") int page,
            @ToolParam(required = false, description = "内部链路关联上下文，请勿由模型设置")
            String _agent_traceparent) {
        try (var ignored = telemetry.startToolCall("pexelsCuratedPhotos", _agent_traceparent)) {
            PexelsSearchResponse response = pexelsPhotoService.curatedPhotos(perPage, page);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("pexelsCuratedPhotos failed", e);
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    String pexelsCuratedPhotos(int perPage, int page) {
        return pexelsCuratedPhotos(perPage, page, null);
    }

    @Tool(description = "获取单张 Pexels 图片的详细信息")
    public String pexelsGetPhoto(
            @ToolParam(description = "Pexels 图片ID") int photoId,
            @ToolParam(required = false, description = "内部链路关联上下文，请勿由模型设置")
            String _agent_traceparent) {
        try (var ignored = telemetry.startToolCall("pexelsGetPhoto", _agent_traceparent)) {
            PexelsPhotoDTO response = pexelsPhotoService.getPhoto(photoId);
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("pexelsGetPhoto failed: photoId={}", photoId, e);
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    String pexelsGetPhoto(int photoId) {
        return pexelsGetPhoto(photoId, null);
    }

    /**
     * Minimal JSON string escaping — replaces backslash, double-quote, and control chars.
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
