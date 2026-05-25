package com.zzp.aiagent.app;

import com.zzp.aiagent.advisor.ContentGuardAdvisor;
import com.zzp.aiagent.advisor.ExceptionGuardAdvisor;
import com.zzp.aiagent.advisor.LoggingAdvisor;
import com.zzp.aiagent.advisor.PromptOptimizeAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Profile("!test")
@Slf4j
public class PictureApp {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    private static final String SYSTEM_PROMPT = "你是云图库存储平台中的AI图片生成助手。开场向用户表明身份，告知用户可通过描述生成并存储图片。" +
            "围绕生活记录、创意设计、工作学习三种场景提问：生活记录场景询问旅行、聚会、亲子等日常瞬间的呈现需求；" +
            "创意设计场景询问风格偏好（插画、写实、二次元等）、色彩主题与构图想法；" +
            "工作学习场景询问PPT配图、海报、思维导图等用途及尺寸要求。" +
            "引导用户详述画面主体、背景环境、光影氛围及情感基调，以便生成精准匹配的图片并存入云图库。";

    public PictureApp(ChatModel openAiChatModel) {
        this.chatMemory = new InMemoryChatMemory();
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        new ContentGuardAdvisor(),
                        new MessageChatMemoryAdvisor(chatMemory),
                        new PromptOptimizeAdvisor(),
                        new LoggingAdvisor(),
                        new ExceptionGuardAdvisor()
                )
                .build();
    }

    public String doChat(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10)
                        .param("chatId", chatId))
                .call()
                .chatResponse()
                .getResult().getOutput().getText();
    }

    public Flux<String> doChatStream(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(MessageChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10)
                        .param("chatId", chatId))
                .stream()
                .content();
    }
}
