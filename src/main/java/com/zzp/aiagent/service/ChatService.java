package com.zzp.aiagent.service;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import reactor.core.publisher.Flux;

public interface ChatService {
    ChatResponseVO chat(ChatRequest request, String chatId);
    Flux<StreamEventVO> chatStream(ChatRequest request, String chatId);
}
