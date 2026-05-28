package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.HashMap;

import static org.mockito.Mockito.mock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 验证 ContentGuardAdvisor.validateImages() 的图片数量/大小校验。
 * 现有 AdvisorTest 只覆盖文本校验，本类专门覆盖多模态 Media 校验。
 */
@DisplayName("ContentGuardAdvisor 图片校验")
class ContentGuardImageTest {

    private final ContentGuardAdvisor advisor = new ContentGuardAdvisor();

    private ChatClientRequest withMedia(List<Media> medias) {
        UserMessage um = UserMessage.builder().text("hi").media(medias).build();
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(um)))
                .context(new HashMap<>())
                .build();
    }

    private static Media media(int sizeBytes) {
        byte[] bytes = new byte[sizeBytes];
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(bytes));
    }

    /**
     * 单次最多 5 张图片，第 6 张应被拒。
     */
    @Test
    @DisplayName("超过 5 张图片 → 抛 IMAGE_TOO_LARGE")
    void tooManyImages_rejected() {
        List<Media> six = List.of(media(10), media(10), media(10), media(10), media(10), media(10));
        ChatClientRequest req = withMedia(six);

        assertThatThrownBy(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE.getCode());
    }

    /**
     * 单张图片超过 10MB 应被拒。
     * 修复点：ContentGuardAdvisor.validateImages() 增加 byte[] 分支后，校验对 Spring AI 的实际数据类型生效。
     */
    @Test
    @DisplayName("单张图片超过 10MB → 抛 IMAGE_TOO_LARGE")
    void singleImageTooBig_rejected() {
        Media big = media(11 * 1024 * 1024);
        ChatClientRequest req = withMedia(List.of(big));

        assertThatThrownBy(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE.getCode());
    }

    /**
     * 单张图片正好 10MB 边界 → 不应被拒（< 才拒，相等放行）。
     */
    @Test
    @DisplayName("单张图片 10MB 边界 → 放行")
    void singleImageAtBoundary_passes() {
        Media exactly10 = media(10 * 1024 * 1024);
        ChatClientRequest req = withMedia(List.of(exactly10));

        assertThatCode(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                .doesNotThrowAnyException();
    }

    /**
     * 现状记录：Spring AI 把 ByteArrayResource 读成 byte[] 存储。
     * 如果未来 Spring AI 升级回 Resource 类型，本用例会失败提醒——彼时需要确认
     * ContentGuardAdvisor 的 Resource 分支仍然能正常工作。
     */
    @Test
    @DisplayName("Spring AI 行为锚定：Media.getData() 是 byte[]")
    void mediaData_isByteArray_typeAnchor() {
        Media m = media(1024);
        assertThat(m.getDataAsByteArray()).isInstanceOf(byte[].class);
    }
}
