package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;

/**
 * 异常兜底：将Advisor链内抛出的异常转为友好的AssistantMessage，对客户端透明。
 * order=MAX保证在最外层包裹整条链；流式.onErrorResume顺序：具体异常在前，泛型Throwable在后。
 */
@Slf4j
public class ExceptionGuardAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        try {
            return chain.nextAroundCall(request);
        } catch (BusinessException e) {
            log.warn("[ExceptionGuard] 业务异常 code={}", e.getCode());
            return friendlyResponse(e.getMessage());
        } catch (Exception e) {
            log.error("[ExceptionGuard] 未知异常", e);
            return friendlyResponse(ErrorCode.SYSTEM_ERROR.getMessage());
        }
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        // Flux.defer确保chain.nextAroundStream的同步异常（如ContentGuard的validate）
        // 也被转换为Flux内错误信号，从而使下方的.onErrorResume可以捕获
        return Flux.defer(() -> chain.nextAroundStream(request)
                .onErrorResume(BusinessException.class, e -> {
                    log.warn("[ExceptionGuard-Stream] 业务异常 code={}", e.getCode());
                    return Flux.just(friendlyResponse(e.getMessage()));
                })
                .onErrorResume(e -> {
                    log.error("[ExceptionGuard-Stream] 未知异常", e);
                    return Flux.just(friendlyResponse(ErrorCode.SYSTEM_ERROR.getMessage()));
                }));
    }

    private AdvisedResponse friendlyResponse(String message) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(message))));
        return AdvisedResponse.builder()
                .response(chatResponse)
                .adviseContext(new HashMap<>())
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
