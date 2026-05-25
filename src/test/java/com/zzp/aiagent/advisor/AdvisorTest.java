package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.ContentSafetyException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.exception.InvalidInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Advisor 链单元测试")
class AdvisorTest {

    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
    }

    private AdvisedRequest requestWithUserText(String text) {
        Prompt prompt = text != null
                ? new Prompt(List.of(new UserMessage(text)))
                : new Prompt(List.of(new UserMessage("")));
        return AdvisedRequest.builder()
                .chatModel(chatModel)
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

    // ─── ContentGuardAdvisor ─────────────────────────────────────

    @Nested
    @DisplayName("ContentGuardAdvisor")
    class ContentGuardTest {

        private final ContentGuardAdvisor advisor = new ContentGuardAdvisor();

        @Test
        @DisplayName("空消息 → 抛出 InvalidInputException")
        void emptyMessage() {
            AdvisedRequest req = requestWithUserText("");

            assertThatThrownBy(() -> advisor.aroundCall(req, chain -> null))
                    .isInstanceOf(InvalidInputException.class)
                    .extracting(e -> ((InvalidInputException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("null 消息 → 抛出 InvalidInputException")
        void nullMessage() {
            AdvisedRequest req = requestWithUserText(null);

            assertThatThrownBy(() -> advisor.aroundCall(req, chain -> null))
                    .isInstanceOf(InvalidInputException.class)
                    .extracting(e -> ((InvalidInputException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("超长消息 → 抛出 InvalidInputException")
        void tooLongMessage() {
            String longMsg = "a".repeat(2001);
            AdvisedRequest req = requestWithUserText(longMsg);

            assertThatThrownBy(() -> advisor.aroundCall(req, chain -> null))
                    .isInstanceOf(InvalidInputException.class)
                    .extracting(e -> ((InvalidInputException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_TOO_LONG);
        }

        @Test
        @DisplayName("敏感词 → 抛出 ContentSafetyException")
        void blockedKeyword() {
            AdvisedRequest req = requestWithUserText("我想生成暴力图片");

            assertThatThrownBy(() -> advisor.aroundCall(req, chain -> null))
                    .isInstanceOf(ContentSafetyException.class)
                    .extracting(e -> ((ContentSafetyException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONTENT_BLOCKED);
        }

        @Test
        @DisplayName("合法消息 → 放行到下一链路")
        void validMessage_passesThrough() {
            AdvisedRequest req = requestWithUserText("帮我生成一张雪景图");
            CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
            AdvisedResponse expected = emptyResponse();
            when(chain.nextAroundCall(req)).thenReturn(expected);

            AdvisedResponse actual = advisor.aroundCall(req, chain);

            assertThat(actual).isEqualTo(expected);
            verify(chain).nextAroundCall(req);
        }

        @Test
        @DisplayName("order 应为 0（最优先执行）")
        void orderIsZero() {
            assertThat(advisor.getOrder()).isEqualTo(0);
        }
    }

    // ─── PromptOptimizeAdvisor ────────────────────────────────────

    @Nested
    @DisplayName("PromptOptimizeAdvisor")
    class PromptOptimizeTest {

        private final PromptOptimizeAdvisor advisor = new PromptOptimizeAdvisor();

        @Test
        @DisplayName("正常消息 → 改写 userText 为优化版 prompt")
        void normalMessage_getsOptimized() {
            AdvisedRequest req = requestWithUserText("雪景");
            CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
            when(chain.nextAroundCall(any())).thenReturn(emptyResponse());

            advisor.aroundCall(req, chain);

            verify(chain, times(1)).nextAroundCall(any());
        }

        @Test
        @DisplayName("优化后 prompt 写入 adviseContext 共享给下游")
        void optimizedPrompt_sharedInContext() {
            AdvisedRequest req = requestWithUserText("冬日雪景");
            CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
            AdvisedResponse rawResponse = emptyResponse();
            when(chain.nextAroundCall(any())).thenReturn(rawResponse);

            AdvisedResponse response = advisor.aroundCall(req, chain);

            assertThat(response.adviseContext()).containsKey("optimizedPrompt");
            String optimized = (String) response.adviseContext().get("optimizedPrompt");
            assertThat(optimized).contains("画面主体", "环境背景", "冬日雪景");
        }

        @Test
        @DisplayName("空消息 → 不做改写，原样放行")
        void emptyMessage_passesThrough() {
            AdvisedRequest req = requestWithUserText("");
            CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
            when(chain.nextAroundCall(req)).thenReturn(emptyResponse());

            advisor.aroundCall(req, chain);

            verify(chain).nextAroundCall(req);
        }

        @Test
        @DisplayName("order 应为 20")
        void orderIsTwenty() {
            assertThat(advisor.getOrder()).isEqualTo(20);
        }
    }

    // ─── LoggingAdvisor ───────────────────────────────────────────

    @Nested
    @DisplayName("LoggingAdvisor")
    class LoggingTest {

        private final LoggingAdvisor advisor = new LoggingAdvisor();

        @Test
        @DisplayName("非流式：正常执行 → 记录请求和响应日志")
        void normalExecution_logsRequestAndResponse() {
            AdvisedRequest req = requestWithUserText("hello");
            CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
            when(chain.nextAroundCall(any())).thenReturn(emptyResponse());

            AdvisedResponse response = advisor.aroundCall(req, chain);

            assertThat(response).isNotNull();
            String text = response.response().getResult().getOutput().getText();
            assertThat(text).isEqualTo("ok");
            verify(chain, times(1)).nextAroundCall(any());
        }

        @Test
        @DisplayName("流式：MessageAggregator 聚合并记录完整响应")
        void stream_aggregatesAndLogs() {
            AdvisedRequest req = requestWithUserText("hello");
            StreamAroundAdvisorChain chain = mock(StreamAroundAdvisorChain.class);
            AdvisedResponse chunk = emptyResponse();
            when(chain.nextAroundStream(any())).thenReturn(Flux.just(chunk, chunk));

            Flux<AdvisedResponse> result = advisor.aroundStream(req, chain);

            StepVerifier.create(result)
                    .expectNextCount(2)
                    .verifyComplete();
        }

        @Test
        @DisplayName("order 应为 30")
        void orderIsThirty() {
            assertThat(advisor.getOrder()).isEqualTo(30);
        }
    }

    // ─── ExceptionGuardAdvisor ────────────────────────────────────

    @Nested
    @DisplayName("ExceptionGuardAdvisor")
    class ExceptionGuardTest {

        private final ExceptionGuardAdvisor advisor = new ExceptionGuardAdvisor();

        @Test
        @DisplayName("正常执行 → 原样返回")
        void normalExecution_passesThrough() {
            AdvisedRequest req = requestWithUserText("hello");
            CallAroundAdvisorChain chain = mock(CallAroundAdvisorChain.class);
            AdvisedResponse expected = emptyResponse();
            when(chain.nextAroundCall(req)).thenReturn(expected);

            AdvisedResponse actual = advisor.aroundCall(req, chain);

            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("业务异常 AiAgentException → 转为友好回复")
        void businessException_returnsFriendlyResponse() {
            AdvisedRequest req = requestWithUserText("test");
            CallAroundAdvisorChain chain = r -> {
                throw new InvalidInputException(ErrorCode.EMPTY_MESSAGE);
            };

            AdvisedResponse response = advisor.aroundCall(req, chain);

            String text = response.response().getResult().getOutput().getText();
            assertThat(text).isEqualTo(ErrorCode.EMPTY_MESSAGE.getUserMessage());
        }

        @Test
        @DisplayName("未知异常 → 转为 9999 友好回复，不暴露 stacktrace")
        void unknownException_returnsInternalError() {
            AdvisedRequest req = requestWithUserText("test");
            CallAroundAdvisorChain chain = r -> {
                throw new RuntimeException("模拟未知错误");
            };

            AdvisedResponse response = advisor.aroundCall(req, chain);

            String text = response.response().getResult().getOutput().getText();
            assertThat(text).isEqualTo(ErrorCode.INTERNAL_ERROR.getUserMessage());
        }

        @Test
        @DisplayName("流式：异常 → 降级为单条友好消息 (Reactor onErrorResume)")
        void streamException_returnsFallback() {
            AdvisedRequest req = requestWithUserText("test");
            StreamAroundAdvisorChain chain = r ->
                    Flux.error(new InvalidInputException(ErrorCode.MESSAGE_TOO_LONG));

            Flux<AdvisedResponse> result = advisor.aroundStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> {
                        String text = r.response().getResult().getOutput().getText();
                        assertThat(text).isEqualTo(ErrorCode.MESSAGE_TOO_LONG.getUserMessage());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("流式：未知异常 → 降级为内部错误消息")
        void streamUnknownError_returnsInternalError() {
            AdvisedRequest req = requestWithUserText("test");
            StreamAroundAdvisorChain chain = r ->
                    Flux.error(new NullPointerException("模拟空指针"));

            Flux<AdvisedResponse> result = advisor.aroundStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> {
                        String text = r.response().getResult().getOutput().getText();
                        assertThat(text).isEqualTo(ErrorCode.INTERNAL_ERROR.getUserMessage());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("order 应为 Integer.MAX_VALUE（最后执行）")
        void orderIsMax() {
            assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE);
        }
    }
}
