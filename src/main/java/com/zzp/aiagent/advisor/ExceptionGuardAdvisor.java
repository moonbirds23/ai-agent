package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.AiAgentException;
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
 * <h3>职责</h3>
 * 捕获链内所有异常，转为用户友好的 ChatResponse，绝不往外泄漏 stacktrace。
 *
 * <h3>M6 注意</h3>
 * {@link AdvisedResponse.Builder#adviseContext(Map)} 不能传 null，
 * 必须显式设 new HashMap<>()，否则抛 IllegalArgumentException。
 *
 */
@Slf4j
public class ExceptionGuardAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    /**
     * 非流式容错：try-catch 双分支。
     * AiAgentException（已知业务异常）→ warn 级别，不打印堆栈。
     * Exception（未知异常）→ error 级别，打印完整堆栈用于排查。
     */
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        try {
            return chain.nextAroundCall(request);
        } catch (AiAgentException e) {
            log.warn("[ExceptionGuard] 业务异常 code={}", e.getErrorCode().getCode());
            return friendlyResponse(e.getErrorCode().getUserMessage());
        } catch (Exception e) {
            log.error("[ExceptionGuard] 未知异常", e);
            return friendlyResponse(ErrorCode.INTERNAL_ERROR.getUserMessage());
        }
    }

    /**
     * 流式容错：双 onErrorResume。
     * 必须先匹配子类 (AiAgentException)，再匹配兜底 (Throwable)。
     * 如果顺序反了，AiAgentException 会被兜底分支吞掉，日志丢失分类信息。
     */
    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(request)
                // 第一层：已知业务异常，按具体 errorCode 返回对应提示
                .onErrorResume(AiAgentException.class, e -> {
                    log.warn("[ExceptionGuard-Stream] 业务异常 code={}", e.getErrorCode().getCode());
                    return Flux.just(friendlyResponse(e.getErrorCode().getUserMessage()));
                })
                // 第二层：未知异常，统一返回 9999 通用错误提示，不暴露细节
                .onErrorResume(e -> {
                    log.error("[ExceptionGuard-Stream] 未知异常", e);
                    return Flux.just(friendlyResponse(ErrorCode.INTERNAL_ERROR.getUserMessage()));
                });
    }

    /**
     * 构造降级 ChatResponse。
     * 将错误信息包装为 AssistantMessage 返回给用户，
     * 用户看到的是友好提示而非异常堆栈。
     */
    private AdvisedResponse friendlyResponse(String message) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(message))));
        // adviseContext 不能为 null（M6 强制校验）
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
