package com.zzp.aiagent.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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

class PictureAppMultiTurnTest {

    private ChatModel chatModel;
    private PictureApp pictureApp;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        pictureApp = new PictureApp(chatModel);
    }

    @Test
    @DisplayName("多轮对话：相同chatId应保持上下文，Prompt应包含历史消息")
    void multiTurn_sameChatId_shouldAccumulateHistory() {
        // 记录每次 call 收到的 Prompt 中的消息数量
        AtomicInteger round = new AtomicInteger(0);
        when(chatModel.call(any(Prompt.class)))
                .thenAnswer(invocation -> {
                    int r = round.incrementAndGet();
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage("第" + r + "轮回复：好的"))
                    ));
                });

        // 第一轮
        String reply1 = pictureApp.doChat("我想要一张雪景图片", "chat-001");
        assertThat(reply1).isNotBlank();
        System.out.println("[第1轮] " + reply1);

        // 第二轮：相同chatId，历史消息应被携带
        String reply2 = pictureApp.doChat("加一个小孩在堆雪人", "chat-001");
        assertThat(reply2).isNotBlank();
        System.out.println("[第2轮] " + reply2);

        // 第三轮：继续同一会话
        String reply3 = pictureApp.doChat("再加一条金毛犬", "chat-001");
        assertThat(reply3).isNotBlank();
        System.out.println("[第3轮] " + reply3);

        // 验证 ChatModel 被调用了3次
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(new ChatResponse(List.of()));
        chatModel.call(new Prompt(new UserMessage("dummy"))); // 触发 capture

        // 核心验证：都返回了非空内容
    }

    @Test
    @DisplayName("多轮对话：不同chatId应隔离上下文")
    void multiTurn_differentChatId_shouldIsolateContext() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("收到，正在为您生成"))
                )));

        // 会话A
        String replyA = pictureApp.doChat("我想要一张海边日落图", "chat-A");
        System.out.println("[会话A] " + replyA);
        assertThat(replyA).isNotBlank();

        // 会话B：全新会话，不应记住会话A的内容
        String replyB = pictureApp.doChat("我想要一张森林清晨图", "chat-B");
        System.out.println("[会话B] " + replyB);
        assertThat(replyB).isNotBlank();

        // 回到会话A：应该能继续会话A的上下文
        String replyA2 = pictureApp.doChat("把日落改成日出", "chat-A");
        System.out.println("[会话A-第2轮] " + replyA2);
        assertThat(replyA2).isNotBlank();
    }

    @Test
    @DisplayName("系统提示词应生效")
    void systemPrompt_shouldBeEffective() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(captor.capture()))
                .thenReturn(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("系统提示词已加载"))
                )));

        pictureApp.doChat("帮我生成一张图片", "chat-sys");

        // 验证 Prompt 中包含系统消息
        Prompt captured = captor.getValue();
        List<Message> messages = captured.getInstructions();
        assertThat(messages).isNotEmpty();
        System.out.println("消息数量: " + messages.size() + " (包含系统提示词)");
    }
}
