package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;

@Slf4j
public class ExceptionGuardAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        try {
            return chain.nextCall(request);
        } catch (BusinessException e) {
            log.warn("[ExceptionGuard] 业务异常 code={}", e.getCode());
            return friendlyResponse(e.getMessage());
        } catch (Exception e) {
            log.error("[ExceptionGuard] 未知异常", e);
            return friendlyResponse(ErrorCode.SYSTEM_ERROR.getMessage());
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> chain.nextStream(request)
                .onErrorResume(BusinessException.class, e -> {
                    log.warn("[ExceptionGuard-Stream] 业务异常 code={}", e.getCode());
                    return Flux.just(friendlyResponse(e.getMessage()));
                })
                .onErrorResume(e -> {
                    log.error("[ExceptionGuard-Stream] 未知异常", e);
                    return Flux.just(friendlyResponse(ErrorCode.SYSTEM_ERROR.getMessage()));
                }));
    }

    private ChatClientResponse friendlyResponse(String message) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(message))));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(new HashMap<>())
                .build();
    }

    @Override
    public String getName() {
        return "ExceptionGuard";
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
