package com.zzp.aiagent.app;

import com.zzp.aiagent.common.PromptTemplate;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.image.ImageGenerationService;
import com.zzp.aiagent.image.VisionAnalysisService;
import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.rag.PromptReferenceAssembler;
import com.zzp.aiagent.rag.RagService;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 专项验证多模态链路：
 *  1) 图生图（讨论模式 + 图片 base64/URL）→ Prompt 中携带 Media。
 *  2) 文生图（生成模式）→ 触发 ImageGenerationService.generate，并返回 image_generated VO。
 *  3) 非空图片输入但空文本时的处理（image-only 请求）。
 */
@DisplayName("PictureApp 多模态路径")
class PictureAppMultimodalTest {

    private ChatModel chatModel;
    private ImageGenerationService imageGenService;
    private VisionAnalysisService visionAnalysisService;
    private RagService ragService;
    private PromptReferenceAssembler assembler;
    private GalleryService galleryService;
    private PictureApp pictureApp;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        imageGenService = mock(ImageGenerationService.class);
        visionAnalysisService = mock(VisionAnalysisService.class);
        ragService = mock(RagService.class);
        assembler = mock(PromptReferenceAssembler.class);
        galleryService = mock(GalleryService.class);
        when(ragService.buildContext(any())).thenReturn(com.zzp.aiagent.rag.model.RagContext.empty());
        when(assembler.assemble(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        pictureApp = new PictureApp(chatModel, MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(100)
                .build(), new PromptTemplate(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                imageGenService, visionAnalysisService, ragService, assembler, galleryService);
    }

    private static String jsonChat(String message) {
        return "{\"type\":\"chat\",\"message\":\"" + message
                + "\",\"imagePrompt\":null,\"style\":null,\"dimensions\":null,\"revisedPrompt\":null}";
    }

    private static String jsonImageReady() {
        return "{\"type\":\"image_ready\",\"message\":\"已生成\","
                + "\"imagePrompt\":\"snowy mountain at dawn\","
                + "\"style\":\"写实\",\"dimensions\":\"1024x1024\",\"revisedPrompt\":null}";
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ArgumentCaptor<Prompt> stubCall(String json) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(json));
        return captor;
    }

    // ── 图生图：base64 路径 ─────────────────────────────────────────

    /**
     * 目的：用户上传 base64 图片 + 文字 → Prompt 中应包含携带 Media 的 UserMessage。
     * 实现：构造 1x1 PNG bytes → base64 → ChatRequest(imageBase64) → doChat → 捕获 Prompt 断言 media 非空。
     * 结果：发往 LLM 的 UserMessage 的 media.size() == 1。
     */
    @Test
    @DisplayName("讨论模式 + base64 图片 → Prompt 携带 Media")
    void discussion_withBase64Image_promptContainsMedia() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(jsonChat("已收到您上传的图片")));

        byte[] tinyPng = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String b64 = Base64.getEncoder().encodeToString(tinyPng);
        ChatRequest req = new ChatRequest("帮我把这张图改成水墨风", null, false, b64, null);

        ChatResponseVO vo = pictureApp.doChat(req, "chat-img-1");

        assertThat(vo).isNotNull();
        assertThat(vo.type()).isEqualTo("chat");
        verify(chatModel, times(1)).call(captor.capture());
        Prompt prompt = captor.getValue();
        UserMessage lastUser = (UserMessage) prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(lastUser.getMedia()).hasSize(1);
    }

    // ── 图生图：URL 路径 ────────────────────────────────────────────

    /**
     * 目的：用户传 imageUrl → Prompt 中应包含 Media（哪怕 URL 无法实际下载，构造阶段不会抛）。
     */
    @Test
    @DisplayName("讨论模式 + imageUrl → Prompt 携带 Media")
    void discussion_withImageUrl_promptContainsMedia() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(jsonChat("已收到图片URL")));

        ChatRequest req = new ChatRequest("分析这张图", null, false, null,
                "https://example.com/test.png");

        pictureApp.doChat(req, "chat-img-2");

        verify(chatModel).call(captor.capture());
        Prompt prompt = captor.getValue();
        UserMessage lastUser = (UserMessage) prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(lastUser.getMedia()).hasSize(1);
    }

    /**
     * 目的：无效 URL 不应中断流程，只是不附图片（PictureApp.buildUserSpec() 仅 log.warn）。
     */
    @Test
    @DisplayName("讨论模式 + 非法 imageUrl → 不抛异常，Media 为空")
    void discussion_withInvalidImageUrl_noMediaAttached() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(jsonChat("ok")));

        ChatRequest req = new ChatRequest("分析这张图", null, false, null, "not-a-url://garbage");

        pictureApp.doChat(req, "chat-img-3");

        verify(chatModel).call(captor.capture());
        UserMessage lastUser = (UserMessage) captor.getValue().getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(lastUser.getMedia()).isEmpty();
    }

    // ── 文生图：生成模式 ────────────────────────────────────────────

    /**
     * 目的：生成模式 → LLM 返回 image_ready JSON → 触发 ImageGenerationService.generate → 返回 image_generated VO。
     * 实现：mock LLM 返回 image_ready JSON，mock generate 返回 ImageGenerationResult。
     * 结果：VO.type == image_generated，imageUrl/imageBase64 透传自生图结果。
     */
    @Test
    @DisplayName("生成模式 → 调用 ImageGenerationService → 返回 image_generated VO")
    void generationMode_invokesImageService_andReturnsImageVO() {
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(jsonImageReady()));
        when(imageGenService.generate(eq("snowy mountain at dawn"), eq("写实"), eq("1024x1024")))
                .thenReturn(new ImageGenerationResult(
                        "https://cdn.example.com/img.png", null, null, Map.of()));

        ChatRequest req = new ChatRequest("开始生成", null, true, null, null);

        ChatResponseVO vo = pictureApp.doChat(req, "chat-gen-1");

        assertThat(vo.type()).isEqualTo("image_generated");
        assertThat(vo.imageUrl()).isEqualTo("https://cdn.example.com/img.png");
        assertThat(vo.message()).isEqualTo("已生成");
        verify(imageGenService).generate("snowy mountain at dawn", "写实", "1024x1024");
    }

    /**
     * 目的：生成模式 + ImageGenerationService 抛 BusinessException → 直接冒泡到 Controller（GlobalExceptionHandler 兜底）。
     * 实现：mock generate 抛 BusinessException(IMAGE_GENERATION_FAILED) → 断言 doChat 抛同样异常。
     */
    @Test
    @DisplayName("生成模式 + 生图服务异常 → 抛 BusinessException")
    void generationMode_serviceFails_throwsBusinessException() {
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(jsonImageReady()));
        when(imageGenService.generate(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, "DALL·E 限流"));

        ChatRequest req = new ChatRequest("开始生成", null, true, null, null);

        assertThatThrownBy(() -> pictureApp.doChat(req, "chat-gen-2"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.IMAGE_GENERATION_FAILED.getCode());
    }

    /**
     * 目的：图片分析模式 → 调用 VisionAnalysisService，不触发 ImageGenerationService。
     */
    @Test
    @DisplayName("图片分析模式 → 返回 image_analyzed 和可复用 Prompt")
    void imageAnalysisMode_invokesVisionService_andReturnsPrompt() {
        byte[] tinyPng = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String b64 = Base64.getEncoder().encodeToString(tinyPng);
        when(visionAnalysisService.analyze(eq("分析这张图"), eq(b64), eq(null)))
                .thenReturn(new VisionAnalysisResult("已完成图片分析", "雪山", "日出", "写实",
                        "冷蓝色", "居中构图", "逆光", "宁静", "snow mountain sunrise"));

        ChatRequest req = new ChatRequest("分析这张图", null, false, b64, null,
                ChatRequest.MODE_IMAGE_ANALYSIS);

        ChatResponseVO vo = pictureApp.doChat(req, "chat-analysis-1");

        assertThat(vo.type()).isEqualTo("image_analyzed");
        assertThat(vo.imagePrompt()).isEqualTo("snow mountain sunrise");
        assertThat(vo.revisedPrompt()).contains("主体：雪山", "可复用 Prompt：snow mountain sunrise");
        verify(visionAnalysisService).analyze("分析这张图", b64, null);
        verify(imageGenService, never()).generate(any(), any(), any());
    }

    /**
     * 目的：图片分析模式无图片 → 直接返回参数错误，不调用外部模型。
     */
    @Test
    @DisplayName("图片分析模式 + 无图片 → 抛 BusinessException")
    void imageAnalysisMode_withoutImage_throwsBusinessException() {
        ChatRequest req = new ChatRequest("分析这张图", null, false, null, null,
                ChatRequest.MODE_IMAGE_ANALYSIS);

        assertThatThrownBy(() -> pictureApp.doChat(req, "chat-analysis-2"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
        verify(visionAnalysisService, never()).analyze(any(), any(), any());
    }

    /**
     * 目的：讨论模式（generationMode=null/false）下不应触碰 ImageGenerationService。
     */
    @Test
    @DisplayName("讨论模式 → 不触发 ImageGenerationService")
    void discussion_neverCallsImageService() {
        when(chatModel.call(any(Prompt.class))).thenReturn(responseOf(jsonChat("聊天")));

        pictureApp.doChat(new ChatRequest("hi", null, null, null, null), "chat-disc");

        verify(imageGenService, never()).generate(any(), any(), any());
    }
}
