package com.zzp.aiagent.app;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import com.zzp.aiagent.service.ChatService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Profile("!test")
@Deprecated
public class PictureApp {
    private final ChatService chatService;

    public PictureApp(ChatService chatService) {
        this.chatService = chatService;
    }

    @Deprecated
    public ChatResponseVO doChat(ChatRequest request, String chatId) {
        return chatService.chat(request, chatId);
    }

    @Deprecated
    public Flux<StreamEventVO> doChatStream(ChatRequest request, String chatId) {
        return chatService.chatStream(request, chatId);
    }
}
