package com.zzp.aiagent.advisor;

import com.zzp.aiagent.common.PromptTemplate;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
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

    private static AdvisedRequest requestWithText(String text) {
        Prompt prompt = text != null
                ? new Prompt(List.of(new UserMessage(text)))
                : new Prompt(List.of(new UserMessage("")));
        return AdvisedRequest.builder()
                .chatModel(mock(ChatModel.class))
                .userText(text)
                .messages(prompt.getInstructions())
                .adviseContext(new HashMap<>())
                .build();
    }

    private static AdvisedResponse emptyResponse() {
        return AdvisedResponse.builder()
                .response(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))))
                .adviseContext(new HashMap<>())
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
         * 结果：流 emit 1 个 AdvisedResponse 后完成，内容等于预期 chunk。
         */
        @Test
        @DisplayName("合法消息 → 流正常放行")
        void validMessage_streamPassesThrough() {
            AdvisedRequest req = requestWithText("雪景图");
            StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
            AdvisedResponse chunk = emptyResponse();
            when(chain.nextAroundStream(req)).thenReturn(Flux.just(chunk));

            Flux<AdvisedResponse> result = advisor.aroundStream(req, chain);

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
            AdvisedRequest req = requestWithText("");
            StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);

            StepVerifier.create(advisor.aroundStream(req, chain))
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
            AdvisedRequest req = requestWithText("暴力内容");
            StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);

            StepVerifier.create(advisor.aroundStream(req, chain))
                    .expectErrorMatches(e -> e instanceof BusinessException be
                            && be.getCode() == ErrorCode.CONTENT_BLOCKED.getCode())
                    .verify();
        }
    }

    // ─── PromptOptimizeAdvisor.aroundStream ───────────────────────

    @Nested
    @DisplayName("PromptOptimizeAdvisor 流式")
    class PromptOptimizeStreamTest {

        private final PromptOptimizeAdvisor advisor = new PromptOptimizeAdvisor(new PromptTemplate());

        /**
         * 目的：验证流式路径中每个 chunk 都被 attachOptimizedContext 注入 optimizePrompt key。
         * 实现：userText="冬日雪景" → mock 返回 2 个 chunk → aroundStream → StepVerifier 对每个 chunk 断言 adviseContext。
         * 结果：2 个 chunk 的 adviseContext 都含 optimizedPrompt，值为包含"冬日雪景"的优化版 prompt。
         */
        @Test
        @DisplayName("正常消息 → 改写 userText，每个 chunk 注入 optimizedPrompt")
        void normalMessage_enhancesAndAttachesPerChunk() {
            AdvisedRequest req = requestWithText("冬日雪景");
            StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
            AdvisedResponse chunk = emptyResponse();
            when(chain.nextAroundStream(any())).thenReturn(Flux.just(chunk, chunk));

            Flux<AdvisedResponse> result = advisor.aroundStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> {
                        String optimized = (String) r.adviseContext().get("optimizedPrompt");
                        assertThat(optimized).contains("画面主体", "环境背景", "冬日雪景");
                    })
                    .assertNext(r -> {
                        String optimized = (String) r.adviseContext().get("optimizedPrompt");
                        assertThat(optimized).contains("冬日雪景");
                    })
                    .verifyComplete();
        }

        /**
         * 目的：空消息不触发改写，但 attachOptimizedContext 仍会注入空的 optimizedPrompt key。
         * 实现：userText="" → mock Flux.just(chunk) → 断言响应文本为 "ok" 且 adviseContext 含 optimizedPrompt。
         * 结果：流正常完成，文本透传。
         */
        @Test
        @DisplayName("空消息 → 不做改写，流透传（adviseContext 仍会注入 optimizedPrompt）")
        void emptyMessage_passesThrough() {
            AdvisedRequest req = requestWithText("");
            StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
            AdvisedResponse chunk = emptyResponse();
            when(chain.nextAroundStream(any())).thenReturn(Flux.just(chunk));

            Flux<AdvisedResponse> result = advisor.aroundStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> {
                        String text = r.response().getResult().getOutput().getText();
                        assertThat(text).isEqualTo("ok");
                        assertThat(r.adviseContext()).containsKey("optimizedPrompt");
                    })
                    .verifyComplete();
        }
    }
}
