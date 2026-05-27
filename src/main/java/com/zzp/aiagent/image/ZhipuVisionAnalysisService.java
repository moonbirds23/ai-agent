package com.zzp.aiagent.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Primary
@Slf4j
public class ZhipuVisionAnalysisService implements VisionAnalysisService {

    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public ZhipuVisionAnalysisService(@Value("${zhipu.vision.api-key:}") String visionApiKey,
                                      @Value("${zhipu.api-key:}") String apiKey,
                                      @Value("${zhipu.image.api-key:}") String imageApiKey,
                                      @Value("${zhipu.vision.model:glm-4.5v}") String model,
                                      RestTemplateBuilder builder) {
        this.apiKey = firstNonBlank(visionApiKey, apiKey, imageApiKey);
        this.model = model;
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public VisionAnalysisResult analyze(String message, String imageBase64, String imageUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_AUTH_FAILED, "智谱视觉 API Key 未配置");
        }
        String image = imageUrl != null && !imageUrl.isBlank() ? imageUrl : normalizeImageBase64(imageBase64);
        if (image == null || image.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先上传需要分析的图片");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", buildInstruction(message)),
                                Map.of("type", "image_url", "image_url", Map.of("url", image))
                        )
                )),
                "temperature", 0.2
        );

        log.info("[ZhipuVision] 开始分析图片 model={} hasUrl={} messageLen={}",
                model, imageUrl != null && !imageUrl.isBlank(), message == null ? 0 : message.length());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String responseJson = restTemplate.postForObject(API_URL, entity, String.class);
            JsonNode root = mapper.readTree(responseJson);
            if (root.has("error")) {
                String errMsg = root.path("error").path("message").asText("未知错误");
                log.warn("[ZhipuVision] API返回错误: {}", errMsg);
                throw new BusinessException(ErrorCode.IMAGE_ANALYSIS_FAILED, errMsg);
            }
            String content = root.path("choices").path(0).path("message").path("content").asText();
            VisionAnalysisResult result = parseContent(content);
            log.info("[ZhipuVision] 图片分析成功 promptLen={}",
                    result.imagePrompt() == null ? 0 : result.imagePrompt().length());
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            String messageText = extractErrorMessage(e.getResponseBodyAsString());
            log.warn("[ZhipuVision] API请求失败 status={} message={}", e.getStatusCode(), messageText);
            throw new BusinessException(ErrorCode.IMAGE_ANALYSIS_FAILED, "图片分析失败: " + messageText);
        } catch (Exception e) {
            log.error("[ZhipuVision] 图片分析异常", e);
            throw new BusinessException(ErrorCode.IMAGE_ANALYSIS_FAILED, "图片分析失败，请稍后重试");
        }
    }

    private String buildInstruction(String message) {
        String userRequirement = message == null || message.isBlank() ? "用户未提供额外说明。" : message;
        return "请分析用户提供的图片，提取可用于 AI 生图的视觉元素。"
                + "用户补充说明：" + userRequirement + "\n"
                + "只返回 JSON，不要使用 Markdown 代码块。字段包括："
                + "message、subject、scene、style、colors、composition、lighting、mood、imagePrompt。"
                + "imagePrompt 要适合直接交给图片生成模型使用，保留主体、场景、风格、色彩、构图、光影和氛围。";
    }

    private String normalizeImageBase64(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return null;
        }
        String trimmed = imageBase64.trim();
        if (trimmed.startsWith("data:image/")) {
            return trimmed;
        }
        return "data:image/png;base64," + trimmed;
    }

    private VisionAnalysisResult parseContent(String content) throws Exception {
        String json = unwrapJson(content);
        JsonNode node = mapper.readTree(json);
        return new VisionAnalysisResult(
                text(node, "message", "已完成图片分析"),
                text(node, "subject", null),
                text(node, "scene", null),
                text(node, "style", null),
                text(node, "colors", null),
                text(node, "composition", null),
                text(node, "lighting", null),
                text(node, "mood", null),
                text(node, "imagePrompt", null)
        );
    }

    private String unwrapJson(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                text = text.substring(firstNewLine + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? fallback : value;
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            String message = root.path("error").path("message").asText();
            return message == null || message.isBlank() ? "视觉模型返回错误" : message;
        } catch (Exception e) {
            return "视觉模型返回错误";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @Override
    public String getProviderName() {
        return "zhipu-" + model;
    }
}
