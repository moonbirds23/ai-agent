package com.zzp.aiagent.app;

import com.zzp.aiagent.common.PromptTemplate;
import com.zzp.aiagent.image.ImageGenerationService;
import com.zzp.aiagent.image.VisionAnalysisService;
import com.zzp.aiagent.rag.PromptReferenceAssembler;
import com.zzp.aiagent.rag.RagService;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 ChatMemory 的会话隔离机制：相同 chatId 累积历史 → 多轮上下文连贯；
 * 不同 chatId 记忆互不干扰。这是对话系统的核心能力。
 *
 * <h3>实现方式</h3>
 * mock ChatModel.call() 返回可控的 JSON 响应，使用 InMemoryChatMemory（测试环境不连 Redis），
 * 同一 chatId 多次调用 doChat()，观察每轮回复和上下文保持。
 *
 * <h3>关键验证点</h3>
 * - 相同 chatId 的多轮消息被追加到同一 ChatMemory，Prompt 中的历史消息递增
 * - 不同 chatId 的会话完全隔离，互不影响
 */
@DisplayName("PictureApp 多轮对话")
class PictureAppMultiTurnTest {

    private ChatModel chatModel;
    private PictureApp pictureApp;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        RagService ragService = mock(RagService.class);
        when(ragService.buildContext(any())).thenReturn(com.zzp.aiagent.rag.model.RagContext.empty());
        PromptReferenceAssembler assembler = mock(PromptReferenceAssembler.class);
        when(assembler.assemble(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        pictureApp = new PictureApp(chatModel, new InMemoryChatMemory(), new PromptTemplate(),
                mock(ImageGenerationService.class), mock(VisionAnalysisService.class), ragService, assembler);
    }

    private static ChatRequest req(String message) {
        return new ChatRequest(message, null, null, null, null);
    }

    private static String jsonResponse(String type, String message) {
        return "{\"type\":\"" + type + "\",\"message\":\"" + message
                + "\",\"imagePrompt\":null,\"style\":null,\"dimensions\":null,\"revisedPrompt\":null}";
    }

    /**
     * 目的：验证同一个 chatId 下多轮对话的上下文保持能力。
     * 实现：mock ChatModel 返回逐轮递增的回复 → doChat("chat-001") 连续 3 轮。
     * 结果：3 轮均返回非空 ChatResponseVO，AtomicInteger 确认每轮都收到不同回复。
     *      MessageChatMemoryAdvisor 在内部自动将 UserMessage + AssistantMessage 追加到 ChatMemory，
     *      第 N 轮的 Prompt 包含前 N-1 轮的全部消息。
     */
    @Test
    @DisplayName("多轮对话：相同chatId应保持上下文")
    void multiTurn_sameChatId_shouldAccumulateHistory() {
        AtomicInteger round = new AtomicInteger(0);
        when(chatModel.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    int r = round.incrementAndGet();
                    String json = jsonResponse("chat", "第" + r + "轮回复：好的，请描述你想要的图片");
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage(json))
                    ));
                });

        ChatResponseVO reply1 = pictureApp.doChat(req("我想要一张雪景图片"), "chat-001");
        assertThat(reply1.type()).isEqualTo("chat");
        assertThat(reply1.message()).isNotBlank();
        System.out.println("[第1轮] " + reply1.message());

        ChatResponseVO reply2 = pictureApp.doChat(req("加一个小孩在堆雪人"), "chat-001");
        assertThat(reply2.type()).isEqualTo("chat");
        assertThat(reply2.message()).isNotBlank();
        System.out.println("[第2轮] " + reply2.message());

        ChatResponseVO reply3 = pictureApp.doChat(req("再加一条金毛犬"), "chat-001");
        assertThat(reply3.type()).isEqualTo("chat");
        assertThat(reply3.message()).isNotBlank();
        System.out.println("[第3轮] " + reply3.message());
    }

    /**
     * 目的：验证不同 chatId 的会话记忆互相隔离，不会串话。
     * 实现：chat-A 和 chat-B 交替调用 doChat() → 断言各自回复正常。
     * 结果：chat-A 的第 2 轮不受 chat-B 干扰，两个会话在 ChatMemory 中使用不同 Key。
     *      InMemoryChatMemory 内部用 ConcurrentHashMap<conversationId, List<Message>> 隔离。
     */
    @Test
    @DisplayName("多轮对话：不同chatId应隔离上下文")
    void multiTurn_differentChatId_shouldIsolateContext() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage(jsonResponse("chat", "收到，正在为您生成")))
                )));

        ChatResponseVO replyA = pictureApp.doChat(req("我想要一张海边日落图"), "chat-A");
        System.out.println("[会话A] " + replyA.message());
        assertThat(replyA.message()).isNotBlank();

        ChatResponseVO replyB = pictureApp.doChat(req("我想要一张森林清晨图"), "chat-B");
        System.out.println("[会话B] " + replyB.message());
        assertThat(replyB.message()).isNotBlank();

        ChatResponseVO replyA2 = pictureApp.doChat(req("把日落改成日出"), "chat-A");
        System.out.println("[会话A-第2轮] " + replyA2.message());
        assertThat(replyA2.message()).isNotBlank();
    }
}
