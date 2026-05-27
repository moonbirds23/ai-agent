package com.zzp.aiagent.app;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 验证 DeepSeek 视觉能力通过 Spring AI 是否正确透传。
 * 依赖 local profile 的真实 API Key，需联网。
 */
@Disabled("DeepSeek 不再承担稳定视觉分析；保留为手动联调样例，默认测试不跑外部网络/API")
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("DeepSeek 多模态视觉手动测试")
class VisionModelTest {

    @Autowired
    private ChatModel chatModel;

    /**
     * 目的：验证 DeepSeek vision 能力——发图+文字请求，能正确识别图片内容。
     * 实现：从公开 URL 下载图片 → ByteArrayResource → Media → Consumer spec → ChatClient.call() → 断言非空回复。
     * 结果：LLM 返回对图片内容的文字描述。
     */
    @Test
    @DisplayName("图片 → DeepSeek 视觉理解 → 返回文字描述")
    void image_visionReturnDescription() throws Exception {
        // 下载公开测试图片到 byte[]
        String imageUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/300px-PNG_transparency_demonstration_1.png";
        byte[] imageBytes;
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder().uri(URI.create(imageUrl)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            imageBytes = response.body();
        }

        System.out.println("[Vision Test] 图片大小: " + imageBytes.length + " bytes");

        ChatClient client = ChatClient.builder(chatModel).build();
        ChatResponse response = client.prompt()
                .user(spec -> spec
                        .text("请用中文简短描述这张图片里有什么")
                        .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes))))
                .call()
                .chatResponse();

        String text = response.getResult().getOutput().getText();
        System.out.println("[Vision Test] DeepSeek 回复: " + text);

        org.junit.jupiter.api.Assertions.assertNotNull(text);
        org.junit.jupiter.api.Assertions.assertFalse(text.isBlank(), "视觉模型应返回非空描述");
    }

    /**
     * 目的：验证 DeepSeek vision 使用 URL 方式能否正确传递。
     */
    @Test
    @DisplayName("图片 URL → DeepSeek 视觉理解")
    void imageUrl_visionReturnDescription() throws Exception {
        URL imageUrl = new URL("https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/300px-PNG_transparency_demonstration_1.png");

        ChatClient client = ChatClient.builder(chatModel).build();
        ChatResponse response = client.prompt()
                .user(spec -> spec
                        .text("请用中文简短描述这张图片里有什么")
                        .media(new Media(MimeTypeUtils.IMAGE_PNG, imageUrl)))
                .call()
                .chatResponse();

        String text = response.getResult().getOutput().getText();
        System.out.println("[Vision Test URL] DeepSeek 回复: " + text);

        org.junit.jupiter.api.Assertions.assertNotNull(text);
        org.junit.jupiter.api.Assertions.assertFalse(text.isBlank(), "视觉模型应返回非空描述");
    }
}
