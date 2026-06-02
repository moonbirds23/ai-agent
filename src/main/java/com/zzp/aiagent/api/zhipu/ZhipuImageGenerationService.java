package com.zzp.aiagent.api.zhipu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.service.ImageGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Primary
@Slf4j
public class ZhipuImageGenerationService implements ImageGenerationService {

    private static final String API_URL = "https://open.bigmodel.cn/api/paas/v4/images/generations";
    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d+)\\s*[xX×*]\\s*(\\d+)");

    private final String apiKey;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public ZhipuImageGenerationService(@Value("${zhipu.image.api-key:}") String apiKey,
                                        RestTemplateBuilder builder,
                                        ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .build();
        this.mapper = mapper;
    }

    @Override
    public ImageGenerationResult generate(String prompt, String style, String dimensions) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_AUTH_FAILED, "智谱 API Key 未配置");
        }

        String fullPrompt = buildPrompt(prompt, style);
        String size = dimensions != null && !dimensions.isBlank() ? dimensions : "1024x1024";
        size = normalizeSize(size);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", "cogview-4",
                "prompt", fullPrompt,
                "size", size,
                "n", 1
        );

        log.info("[ZhipuImage] 开始生图 promptLen={} style={} size={}", prompt.length(), style, size);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            String responseJson = restTemplate.postForObject(API_URL, entity, String.class);
            JsonNode root = mapper.readTree(responseJson);

            if (root.has("error")) {
                String errMsg = root.path("error").path("message").asText("未知错误");
                log.warn("[ZhipuImage] API返回错误: {}", errMsg);
                throw new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, errMsg);
            }

            String imageUrl = root.path("data").get(0).path("url").asText();
            log.info("[ZhipuImage] 生图成功 url={}", imageUrl);
            return new ImageGenerationResult(imageUrl, null, null,
                    Map.of("provider", "zhipu-cogview-4", "size", size));
        } catch (BusinessException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            String message = extractErrorMessage(e.getResponseBodyAsString());
            log.warn("[ZhipuImage] API请求失败 status={} message={}", e.getStatusCode(), message);
            throw new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, "图片生成失败: " + message);
        } catch (Exception e) {
            log.error("[ZhipuImage] 生图异常", e);
            throw new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, "图片生成失败，请稍后重试");
        }
    }

    private String buildPrompt(String prompt, String style) {
        if (style == null || style.isBlank()) {
            return prompt;
        }
        return prompt + "，" + style + "风格";
    }

    private String normalizeSize(String dimensions) {
        String normalized = dimensions.trim();
        String ratio = normalized.replace("：", ":").replace(" ", "");
        if (ratio.contains("16:9") || ratio.equalsIgnoreCase("landscape")) {
            return "1344x768";
        }
        if (ratio.contains("9:16") || ratio.equalsIgnoreCase("portrait")) {
            return "768x1344";
        }
        if (ratio.contains("1:1") || ratio.equalsIgnoreCase("square")) {
            return "1024x1024";
        }

        Matcher matcher = SIZE_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return "1024x1024";
        }
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width <= 0 || height <= 0) {
            return "1024x1024";
        }
        return normalizeByRatio(width, height);
    }

    private String normalizeByRatio(int width, int height) {
        double ratio = (double) width / height;
        if (ratio >= 1.6) {
            return "1344x768";
        }
        if (ratio <= 0.7) {
            return "768x1344";
        }
        return "1024x1024";
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            String message = root.path("error").path("message").asText();
            return message == null || message.isBlank() ? "图片模型返回错误" : message;
        } catch (Exception e) {
            return "图片模型返回错误";
        }
    }

    @Override
    public String getProviderName() {
        return "zhipu-cogview-4";
    }
}
