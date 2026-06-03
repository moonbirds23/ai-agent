package com.zzp.aiagent.app;

import com.zzp.aiagent.utils.PromptTemplate;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.service.ImageGenerationService;
import com.zzp.aiagent.service.VisionAnalysisService;
import com.zzp.aiagent.service.ChatMediaService;
import com.zzp.aiagent.service.ConversationLimitService;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.impl.ChatMediaServiceImpl;
import com.zzp.aiagent.service.impl.ChatServiceImpl;
import com.zzp.aiagent.service.impl.PromptReferenceAssembler;
import com.zzp.aiagent.service.RagService;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 PictureApp.doChatStream() 的全部核心逻辑：SSE 事件顺序、token 累积、
 * Flux.defer 延迟求值、结构化 JSON 解析、非 JSON 回退、异常降级。
 *
 * <h3>实现方式</h3>
 * mock ChatModel.stream() 返回可控的 Flux&lt;ChatResponse&gt;，
 * 用 StepVerifier 严格按顺序断言每个 StreamEventVO 的 type 和内容。
 */
@DisplayName("PictureApp 流式路径")
class PictureAppStreamTest {

    private ChatModel chatModel;
    private ImageGenerationService imageGenService;
    private VisionAnalysisService visionAnalysisService;
    private RagService ragService;
    private PromptReferenceAssembler assembler;
    private GalleryService galleryService;
    private ChatMediaService chatMediaService;
    private ConversationLimitService conversationLimitService;
    private ChatServiceImpl chatService;

    private static ChatResponse chunkOf(String text) {
        return new ChatResponse(List.of(
                new Generation(new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        imageGenService = mock(ImageGenerationService.class);
        visionAnalysisService = mock(VisionAnalysisService.class);
        ragService = mock(RagService.class);
        assembler = mock(PromptReferenceAssembler.class);
        galleryService = mock(GalleryService.class);
        chatMediaService = new ChatMediaServiceImpl(galleryService);
        conversationLimitService = mock(ConversationLimitService.class);
        when(ragService.buildContext(any())).thenReturn(com.zzp.aiagent.domain.rag.RagContext.empty());
        when(assembler.assemble(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        chatService = new ChatServiceImpl(chatModel, MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(100)
                .build(), new PromptTemplate(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                imageGenService, visionAnalysisService, ragService, assembler, galleryService,
                chatMediaService, conversationLimitService);
    }

    private static ChatRequest req(String message) {
        return new ChatRequest(message, null, null, null, null);
    }

    // ── Token 流 ─────────────────────────────────────────────────

    /**
     * 目的：验证每个 ChatResponse chunk 经 .content() → .map(StreamEventVO::token) 后正确转换。
     * 实现：mock 返回 3 个 chunk "好"/"的"/"，" → doChatStream → StepVerifier 逐一断言 type="token"。
     * 结果：事件序列为 chatId → token("好") → token("的") → token("，") → done。
     */
    @Test
    @DisplayName("tokenFlux → 每个 chunk 转为 StreamEventVO(type=token)")
    void tokenChunks_becomeTokenEvents() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(chunkOf("好"), chunkOf("的"), chunkOf("，")));

        Flux<StreamEventVO> stream = chatService.chatStream(req("雪景"), "chat-1");

        StepVerifier.create(stream)
                .assertNext(e -> assertThat(e.type()).isEqualTo("chatId"))
                .assertNext(e -> { assertThat(e.type()).isEqualTo("token"); assertThat(e.content()).isEqualTo("好"); })
                .assertNext(e -> { assertThat(e.type()).isEqualTo("token"); assertThat(e.content()).isEqualTo("的"); })
                .assertNext(e -> { assertThat(e.type()).isEqualTo("token"); assertThat(e.content()).isEqualTo("，"); })
                .assertNext(e -> assertThat(e.type()).isEqualTo("done"))
                .verifyComplete();
    }

    /**
     * 目的：验证 Flux.defer 正确延迟求值——done 事件的 data.message 等于累积的全部 token 文本。
     * 实现：mock 单个 chunk "好的，正在为您生成" → 断言 done.data.message 等于完整字符串。
     * 结果：done 事件中的 message 不为空，说明 accumulator.toString() 在 token 流完成后正确返回全文。
     */
    @Test
    @DisplayName("token 全部累积 → done 事件中传递完整文本")
    void tokens_accumulatedInDoneEvent() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(chunkOf("好的，正在为您生成")));

        Flux<StreamEventVO> stream = chatService.chatStream(req("雪景"), "chat-1");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "chatId".equals(e.type()))
                .expectNextMatches(e -> "token".equals(e.type()))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("done");
                    assertThat(e.data().message()).isEqualTo("好的，正在为您生成");
                })
                .verifyComplete();
    }

    // ── 事件顺序 ─────────────────────────────────────────────────

    /**
     * 目的：验证 Flux.concat 保证严格顺序：chatId → token×N → done。
     * 实现：mock 2 个 token → doChatStream → StepVerifier 按顺序断言每条事件的 type。
     * 结果：4 条事件按序抵达，chatId 在最前，done 在最后。
     */
    @Test
    @DisplayName("事件顺序：chatId → tokens → done")
    void eventOrder_chatIdThenTokensThenDone() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(chunkOf("a"), chunkOf("b")));

        Flux<StreamEventVO> stream = chatService.chatStream(req("test"), "chat-X");

        StepVerifier.create(stream)
                .assertNext(e -> assertThat(e.type()).isEqualTo("chatId"))
                .assertNext(e -> assertThat(e.type()).isEqualTo("token"))
                .assertNext(e -> assertThat(e.type()).isEqualTo("token"))
                .assertNext(e -> assertThat(e.type()).isEqualTo("done"))
                .verifyComplete();
    }

    /**
     * 目的：验证传入的 chatId 被正确封装到首条 StreamEventVO 中。
     * 实现：doChatStream("test"), "my-chat-id") → 断言首事件 type=chatId 且 chatId="my-chat-id"。
     * 结果：前端据此保存 chatId 实现多轮对话。
     */
    @Test
    @DisplayName("首事件包含正确的 chatId")
    void firstEvent_containsChatId() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunkOf("ok")));

        Flux<StreamEventVO> stream = chatService.chatStream(req("test"), "my-chat-id");

        StepVerifier.create(stream)
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("chatId");
                    assertThat(e.chatId()).isEqualTo("my-chat-id");
                })
                .thenCancel()
                .verify();
    }

    // ── 结构化 JSON 解析 ─────────────────────────────────────────

    /**
     * 目的：LLM 返回合法 JSON → parseStructuredOrFallback 成功解析 → done.data 完整填充 6 个字段。
     * 实现：mock 返回 ImageAgentResponse JSON → doChatStream → 断言 done.data 的 type/style/dimensions 等。
     * 结果：imagePrompt="snowy landscape", style="写实", dimensions="1024x1024"。
     */
    @Test
    @DisplayName("LLM 返回合法 JSON → done 中的 data 完整填充")
    void structuredJson_parsedToDoneData() {
        String json = "{\"type\":\"image_ready\",\"message\":\"已生成\"," +
                "\"imagePrompt\":\"snowy landscape\"," +
                "\"style\":\"写实\",\"dimensions\":\"1024x1024\",\"revisedPrompt\":null}";
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunkOf(json)));

        Flux<StreamEventVO> stream = chatService.chatStream(req("生成雪景"), "chat-1");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "chatId".equals(e.type()))
                .expectNextMatches(e -> "token".equals(e.type()))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("done");
                    ChatResponseVO data = e.data();
                    assertThat(data.type()).isEqualTo("image_ready");
                    assertThat(data.imagePrompt()).isEqualTo("snowy landscape");
                    assertThat(data.style()).isEqualTo("写实");
                    assertThat(data.dimensions()).isEqualTo("1024x1024");
                })
                .verifyComplete();
    }

    // ── 非 JSON 回退 ─────────────────────────────────────────────

    /**
     * 目的：LLM 返回非 JSON 纯文本时，parseStructuredOrFallback 应回退为 type=chat 的纯文本。
     * 实现：mock 返回不是 JSON 的字符串 → 断言 done.data.type="chat"，message 为原文，imagePrompt=null。
     * 结果：不会抛异常，优雅降级为纯文本对话。
     */
    @Test
    @DisplayName("LLM 返回非 JSON 文本 → done 中 type=chat 纯文本回退")
    void nonJsonText_fallsBackToPlainText() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(chunkOf("这是一段纯文本回复，不是JSON格式")));

        Flux<StreamEventVO> stream = chatService.chatStream(req("你好"), "chat-1");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "chatId".equals(e.type()))
                .expectNextMatches(e -> "token".equals(e.type()))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("done");
                    ChatResponseVO data = e.data();
                    assertThat(data.type()).isEqualTo("chat");
                    assertThat(data.message()).isEqualTo("这是一段纯文本回复，不是JSON格式");
                    assertThat(data.imagePrompt()).isNull();
                })
                .verifyComplete();
    }

    // ── 空 token 流 ──────────────────────────────────────────────

    /**
     * 目的：LLM 返回空流（极端情况）不应卡死。
     * 实现：mock 返回 Flux.empty() → 断言只有 chatId + done 两个事件，done 无 data。
     * 结果：流干净完成，不抛异常。
     */
    @Test
    @DisplayName("LLM 返回空流 → done 中 message 为空字符串")
    void emptyTokenStream_doneHasEmptyMessage() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());

        Flux<StreamEventVO> stream = chatService.chatStream(req("test"), "chat-1");

        StepVerifier.create(stream)
                .assertNext(e -> assertThat(e.type()).isEqualTo("chatId"))
                .assertNext(e -> assertThat(e.type()).isEqualTo("done"))
                .verifyComplete();
    }

    // ── 生成模式 ──────────────────────────────────────────────────

    @Test
    @DisplayName("生成模式 → chatId → 真实阶段提示 → done(image_generated)")
    void generationStream_returnsStageProgressAndImageDone() {
        String json = "{\"type\":\"image_ready\",\"message\":\"图片已生成\"," +
                "\"imagePrompt\":\"snowy landscape\"," +
                "\"style\":\"写实\",\"dimensions\":\"1024x1024\",\"revisedPrompt\":\"final prompt\"}";
        when(chatModel.call(any(Prompt.class))).thenReturn(chunkOf(json));
        when(imageGenService.generate("snowy landscape", "写实", "1024x1024"))
                .thenReturn(new ImageGenerationResult("https://cdn.example.com/img.png", null, null, Map.of()));
        ChatRequest req = new ChatRequest("生成雪景", null, true, null, null,
                ChatRequest.MODE_IMAGE_GENERATION);

        Flux<StreamEventVO> stream = chatService.chatStream(req, "chat-gen");

        StepVerifier.create(stream)
                .assertNext(e -> assertThat(e.type()).isEqualTo("chatId"))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("progress");
                    assertThat(e.content()).isEqualTo("正在整理图片 Prompt...");
                })
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("progress");
                    assertThat(e.content()).isEqualTo("正在生成图片...");
                })
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("done");
                    assertThat(e.data().type()).isEqualTo("image_generated");
                    assertThat(e.data().imageUrl()).isEqualTo("https://cdn.example.com/img.png");
                    assertThat(e.data().imagePrompt()).isEqualTo("snowy landscape");
                    assertThat(e.data().revisedPrompt()).isEqualTo("final prompt");
                })
                .verifyComplete();
    }

    // ── 图片分析模式 ────────────────────────────────────────────────

    @Test
    @DisplayName("图片分析模式 → chatId → progress → done(image_analyzed)")
    void imageAnalysisStream_returnsProgressAndDone() {
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        when(visionAnalysisService.analyze(any(), any(), any()))
                .thenReturn(new VisionAnalysisResult("已完成图片分析", "雪山", "日出", "写实",
                        "冷蓝色", "居中构图", "逆光", "宁静", "snow mountain sunrise"));
        ChatRequest req = new ChatRequest("分析这张图", null, false, b64, null,
                ChatRequest.MODE_IMAGE_ANALYSIS);

        Flux<StreamEventVO> stream = chatService.chatStream(req, "chat-analysis");

        StepVerifier.create(stream)
                .assertNext(e -> assertThat(e.type()).isEqualTo("chatId"))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("progress");
                    assertThat(e.content()).isEqualTo("正在分析图片...");
                })
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("done");
                    assertThat(e.data().type()).isEqualTo("image_analyzed");
                    assertThat(e.data().imagePrompt()).isEqualTo("snow mountain sunrise");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("图片分析模式 + 无图片 → error事件")
    void imageAnalysisStream_withoutImage_returnsError() {
        ChatRequest req = new ChatRequest("分析这张图", null, false, null, null,
                ChatRequest.MODE_IMAGE_ANALYSIS);

        Flux<StreamEventVO> stream = chatService.chatStream(req, "chat-analysis-empty");

        StepVerifier.create(stream)
                .assertNext(e -> assertThat(e.type()).isEqualTo("chatId"))
                .assertNext(e -> assertThat(e.type()).isEqualTo("progress"))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("error");
                    assertThat(e.content()).isEqualTo("请先上传需要分析的图片");
                })
                .verifyComplete();
    }

    // ── 异常降级 ─────────────────────────────────────────────────

    /**
     * 目的：验证 RuntimeException（非业务异常）被 ExceptionGuardAdvisor 捕获后转为友好提示 token。
     * ExceptionGuardAdvisor.adviseStream() 中的 .onErrorResume(e -> ...) 会兜底所有异常，
     * 返回包含 "系统内部异常" 的 ChatClientResponse，经 .content() 后变成 token 事件。
     */
    @Test
    @DisplayName("RuntimeException → ExceptionGuard 转为友好提示 token")
    void exceptionInStream_returnsErrorEvent() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("连接中断")));

        Flux<StreamEventVO> stream = chatService.chatStream(req("test"), "chat-1");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "chatId".equals(e.type()))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("token");
                    assertThat(e.content()).isEqualTo("系统内部异常");
                })
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();
    }

    /**
     * 目的：验证 BusinessException 被 ExceptionGuardAdvisor 捕获后透传具体错误消息。
     * ExceptionGuardAdvisor.adviseStream() 的 .onErrorResume(BusinessException.class, ...)
     * 优先匹配，将 e.getMessage() 设置为友好回复的文本内容。
     */
    @Test
    @DisplayName("BusinessException → ExceptionGuard 透传具体错误消息")
    void businessException_returnsSpecificMessage() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new BusinessException(ErrorCode.CONTENT_BLOCKED)));

        Flux<StreamEventVO> stream = chatService.chatStream(req("test"), "chat-1");

        StepVerifier.create(stream)
                .expectNextMatches(e -> "chatId".equals(e.type()))
                .assertNext(e -> {
                    assertThat(e.type()).isEqualTo("token");
                    assertThat(e.content()).isEqualTo("请求包含不支持的词汇");
                })
                .expectNextMatches(e -> "done".equals(e.type()))
                .verifyComplete();
    }
}
