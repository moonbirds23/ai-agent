package com.zzp.aiagent.app;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import com.zzp.aiagent.service.ChatMediaService;
import com.zzp.aiagent.service.ConversationLimitService;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.service.impl.ChatMediaServiceImpl;
import com.zzp.aiagent.service.impl.ChatServiceImpl;
import com.zzp.aiagent.tool.CurrentImageContext;
import com.zzp.aiagent.tool.GalleryAgentTools;
import com.zzp.aiagent.tool.ToolProgressContext;
import com.zzp.aiagent.tool.WebSearchTools;
import com.zzp.aiagent.utils.PromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for ChatServiceImpl with Tool Calling architecture.
 * Verifies single-path chat/stream flow (no more mode routing).
 */
@DisplayName("ChatServiceImpl (Tool Calling)")
class ChatServiceImplTest {

    private ChatModel chatModel;
    private GalleryAgentTools galleryAgentTools;
    private WebSearchTools webSearchTools;
    private GalleryService galleryService;
    private PictureAiProfileService pictureAiProfileService;
    private ChatMediaService chatMediaService;
    private ConversationLimitService conversationLimitService;
    private CurrentImageContext currentImageContext;
    private ToolProgressContext toolProgressContext;
    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        galleryAgentTools = mock(GalleryAgentTools.class);
        webSearchTools = mock(WebSearchTools.class);
        galleryService = mock(GalleryService.class);
        pictureAiProfileService = mock(PictureAiProfileService.class);
        chatMediaService = new ChatMediaServiceImpl(galleryService);
        conversationLimitService = mock(ConversationLimitService.class);
        currentImageContext = new CurrentImageContext();
        toolProgressContext = new ToolProgressContext();

        // Default: model returns plain text (no tool calls)
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("你好！有什么可以帮你的？")))));

        chatService = new ChatServiceImpl(chatModel,
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .maxMessages(100)
                        .build(),
                new PromptTemplate(),
                galleryAgentTools,
                webSearchTools,
                galleryService,
                pictureAiProfileService,
                chatMediaService,
                conversationLimitService,
                currentImageContext,
                toolProgressContext);
    }

    // ── Non-streaming ─────────────────────────────────────────────

    @Nested
    @DisplayName("非流式 chat()")
    class NonStreamingChat {

        @Test
        @DisplayName("纯文本消息 → 返回模型文本回复")
        void plainTextMessage() {
            ChatRequest request = new ChatRequest("你好", null, null, null, null);

            ChatResponseVO result = chatService.chat(request, "test-chat-1");

            assertThat(result.type()).isEqualTo("chat");
            assertThat(result.message()).contains("你好");
        }

        @Test
        @DisplayName("带 chatId → 复用会话记忆")
        void withChatId_reusesMemory() {
            ChatRequest req1 = new ChatRequest("第一条消息", null, null, null, null);
            ChatRequest req2 = new ChatRequest("第二条消息", null, null, null, null);

            chatService.chat(req1, "chat-memory-1");
            ChatResponseVO result2 = chatService.chat(req2, "chat-memory-1");

            assertThat(result2.type()).isEqualTo("chat");
            assertThat(result2.message()).isNotNull();
        }

        @Test
        @DisplayName("生图意图但未调用工具 → 拦截已生成幻觉")
        void generationIntentWithoutTool_shouldGuardHallucinatedResult() {
            when(chatModel.call(any(Prompt.class)))
                    .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                            "图片已生成，您可以点击以下链接查看：[图片链接]")))));
            ChatRequest request = new ChatRequest("帮我生成一张花的图片", null, null, null, null);

            ChatResponseVO result = chatService.chat(request, "guard-chat-1");

            assertThat(result.message()).contains("本轮没有成功调用图片生成工具");
            assertThat(result.message()).doesNotContain("[图片链接]");
        }
    }

    // ── Streaming ─────────────────────────────────────────────────

    @Nested
    @DisplayName("流式 chatStream()")
    class StreamingChat {

        @Test
        @DisplayName("流式 → 先 chatId 事件 → token* → done")
        void stream_emitsChatIdThenTokensThenDone() {
            when(chatModel.stream(any(Prompt.class)))
                    .thenReturn(Flux.just(
                            new ChatResponse(List.of(new Generation(new AssistantMessage("H")))),
                            new ChatResponse(List.of(new Generation(new AssistantMessage("i"))))));

            ChatRequest request = new ChatRequest("hello", null, null, null, null);

            StepVerifier.create(chatService.chatStream(request, "test-stream-1"))
                    .assertNext(event -> assertThat(event.type()).isEqualTo("chatId"))
                    .assertNext(event -> assertThat(event.type()).isEqualTo("token"))
                    .assertNext(event -> assertThat(event.type()).isEqualTo("token"))
                    .assertNext(event -> assertThat(event.type()).isEqualTo("done"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("流式异常 → ExceptionGuard 兜底转为 token + done")
        void streamException_guardedByExceptionAdvisor() {
            when(chatModel.stream(any(Prompt.class)))
                    .thenReturn(Flux.error(new RuntimeException("模拟网络错误")));

            ChatRequest request = new ChatRequest("hello", null, null, null, null);

            // ExceptionGuardAdvisor catches the error → friendly message as token → done
            StepVerifier.create(chatService.chatStream(request, "test-stream-error"))
                    .assertNext(event -> assertThat(event.type()).isEqualTo("chatId"))
                    .assertNext(event -> assertThat(event.type()).isIn("token", "error"))
                    .assertNext(event -> assertThat(event.type()).isIn("done", "error"))
                    .verifyComplete();
        }
    }
}
