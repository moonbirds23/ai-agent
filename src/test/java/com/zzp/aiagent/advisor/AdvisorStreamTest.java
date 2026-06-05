package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 Advisor 在流式路径 (aroundStream) 下的前置逻辑与非流式行为一致，
 * 以及后置逻辑通过 Reactive 操作符正确挂载到 Flux 上。
 *
 * <h3>与非流式测试的区别</h3>
 * - ContentGuard: validate() 同步抛异常（非 Flux.error），必须 assertThatThrownBy
 * - PromptOptimize: 后置用 .map() 而非同步调用，需要对每个 chunk 断言 adviseContext
 */
@DisplayName("Advisor 流式路径 (aroundStream)")
class AdvisorStreamTest {

    private static ChatClientRequest requestWithText(String text) {
        Prompt prompt = text != null
                ? new Prompt(List.of(new UserMessage(text)))
                : new Prompt(List.of(new UserMessage("")));
        return ChatClientRequest.builder()
                .prompt(prompt)
                .context(new HashMap<>())
                .build();
    }

    private static ChatClientResponse emptyResponse() {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))))
                .context(new HashMap<>())
                .build();
    }

    // ─── ContentGuardAdvisor.aroundStream ─────────────────────────

    @Nested
    @DisplayName("ContentGuardAdvisor 流式")
    class ContentGuardStreamTest {

        private final ContentGuardAdvisor advisor = new ContentGuardAdvisor();

        /**
         * 目的：合法消息在流式路径下仍正常放行。
         * 实现：合法消息 → mock chain 返回 Flux.just(chunk) → aroundStream → StepVerifier 验证。
         * 结果：流 emit 1 个 ChatClientResponse 后完成，内容等于预期 chunk。
         */
        @Test
        @DisplayName("合法消息 → 流正常放行")
        void validMessage_streamPassesThrough() {
            ChatClientRequest req = requestWithText("雪景图");
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
            ChatClientResponse chunk = emptyResponse();
            when(chain.nextStream(req)).thenReturn(Flux.just(chunk));

            Flux<ChatClientResponse> result = advisor.adviseStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> assertThat(r).isEqualTo(chunk))
                    .verifyComplete();
        }

        /**
         * 目的：空消息在流式路径下通过 Flux.error 传递异常（而非同步 throw）。
         * 原因：validate() 抛出的 BusinessException 被 catch 后转为 Flux.error()，
         *       确保异常进入响应式流，ExceptionGuardAdvisor 的 .onErrorResume() 可以兜底。
         * 结果：StepVerifier 订阅 Flux 后收到 onError 信号，code=40100。
         */
        @Test
        @DisplayName("空消息流 → 返回 Flux.error(BusinessException)")
        void emptyMessage_returnsFluxError() {
            ChatClientRequest req = requestWithText("");
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

            StepVerifier.create(advisor.adviseStream(req, chain))
                    .expectErrorMatches(e -> e instanceof BusinessException be
                            && be.getCode() == ErrorCode.EMPTY_MESSAGE.getCode())
                    .verify();
        }

        /**
         * 目的：敏感词校验在流式路径下转为 Flux.error，可被 ExceptionGuard 兜底。
         * 结果：Flux 发出 onError(BusinessException)，code=40200。
         */
        @Test
        @DisplayName("敏感词流 → 返回 Flux.error(BusinessException)")
        void blockedKeyword_returnsFluxError() {
            ChatClientRequest req = requestWithText("暴力内容");
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

            StepVerifier.create(advisor.adviseStream(req, chain))
                    .expectErrorMatches(e -> e instanceof BusinessException be
                            && be.getCode() == ErrorCode.CONTENT_BLOCKED.getCode())
                    .verify();
        }
    }

}
