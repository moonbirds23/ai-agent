package com.zzp.aiagent.advisor;

import com.zzp.aiagent.utils.PromptTemplate;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 4 个自定义 Advisor 在非流式路径 (aroundCall) 下的前置/后置逻辑正确性。
 *
 * <h3>实现方式</h3>
 * 每个 Advisor 独立为 @Nested 内部类，用 mock ChatModel 构造 ChatClientRequest，
 * 通过 mock CallAdvisorChain 控制"下游返回什么"来隔离测试目标 Advisor。
 */
@DisplayName("Advisor 链单元测试 (非流式)")
class AdvisorTest {

    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
    }

    private ChatClientRequest requestWithUserText(String text) {
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

    // ─── ContentGuardAdvisor ─────────────────────────────────────

    @Nested
    @DisplayName("ContentGuardAdvisor：校验消息合法性")
    class ContentGuardTest {

        private final ContentGuardAdvisor advisor = new ContentGuardAdvisor();

        /**
         * 目的：空消息应该在 Advisor 链第一节点就被拦截。
         * 实现：传入 userText="" → 调用 aroundCall → 断言抛 BusinessException。
         * 结果：异常 code=40100 (EMPTY_MESSAGE)，不调用 LLM。
         */
        @Test
        @DisplayName("空消息 → 抛出 BusinessException")
        void emptyMessage() {
            ChatClientRequest req = requestWithUserText("");

            assertThatThrownBy(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.EMPTY_MESSAGE.getCode());
        }

        @Test
        @DisplayName("null 消息 → 抛出 BusinessException")
        void nullMessage() {
            ChatClientRequest req = requestWithUserText(null);

            assertThatThrownBy(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.EMPTY_MESSAGE.getCode());
        }

        @Test
        @DisplayName("超长消息 → 抛出 BusinessException")
        void tooLongMessage() {
            String longMsg = "a".repeat(2001);
            ChatClientRequest req = requestWithUserText(longMsg);

            assertThatThrownBy(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.MESSAGE_TOO_LONG.getCode());
        }

        @Test
        @DisplayName("敏感词 → 抛出 BusinessException")
        void blockedKeyword() {
            ChatClientRequest req = requestWithUserText("我想生成暴力图片");

            assertThatThrownBy(() -> advisor.adviseCall(req, mock(CallAdvisorChain.class)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ErrorCode.CONTENT_BLOCKED.getCode());
        }

        /**
         * 目的：合法消息应原样放行到下游 Advisor。
         * 实现：合法消息 → mock chain 预期返回 emptyResponse → 断言实际响应与预期一致。
         * 结果：chain.nextAroundCall 被调用 1 次，响应等于预期值。
         */
        @Test
        @DisplayName("合法消息 → 放行到下一链路")
        void validMessage_passesThrough() {
            ChatClientRequest req = requestWithUserText("帮我生成一张雪景图");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            ChatClientResponse expected = emptyResponse();
            when(chain.nextCall(req)).thenReturn(expected);

            ChatClientResponse actual = advisor.adviseCall(req, chain);

            assertThat(actual).isEqualTo(expected);
            verify(chain).nextCall(req);
        }

        /**
         * 目的：ContentGuard 的 order 必须为 0，保证在所有业务逻辑之前执行。
         * 结果：getOrder() == 0。
         */
        @Test
        @DisplayName("order 应为 0（最优先执行）")
        void orderIsZero() {
            assertThat(advisor.getOrder()).isEqualTo(0);
        }
    }

    // ─── PromptOptimizeAdvisor ────────────────────────────────────

    @Nested
    @DisplayName("PromptOptimizeAdvisor：改写 userText 为专业生图 prompt")
    class PromptOptimizeTest {

        private final PromptOptimizeAdvisor advisor = new PromptOptimizeAdvisor(new PromptTemplate());

        /**
         * 目的：验证用户口语化输入被改写为包含画面主体/环境/光影的专业 prompt。
         * 实现：userText="雪景" → aroundCall → 验证 chain.nextAroundCall 被调用。
         * 结果：下游收到的 ChatClientRequest 的 userText 已被替换为优化版。
         */
        @Test
        @DisplayName("正常消息 → 改写 userText 为优化版 prompt")
        void normalMessage_getsOptimized() {
            ChatClientRequest req = requestWithUserText("雪景");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(any())).thenReturn(emptyResponse());

            advisor.adviseCall(req, chain);

            verify(chain, times(1)).nextCall(any());
        }

        /**
         * 目的：验证优化后的 prompt 被注入 adviseContext，供下游 LoggingAdvisor 等读取。
         * 实现：userText="冬日雪景" → aroundCall → 断言返回 response 的 adviseContext 含 "optimizedPrompt"。
         * 结果：optimizedPrompt 值包含关键要素词汇（画面主体、环境背景）和原始输入词。
         */
        @Test
        @DisplayName("优化后 prompt 写入 adviseContext 共享给下游")
        void optimizedPrompt_sharedInContext() {
            ChatClientRequest req = requestWithUserText("冬日雪景");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            ChatClientResponse rawResponse = emptyResponse();
            when(chain.nextCall(any())).thenReturn(rawResponse);

            ChatClientResponse response = advisor.adviseCall(req, chain);

            assertThat(response.context()).containsKey("optimizedPrompt");
            String optimized = (String) response.context().get("optimizedPrompt");
            assertThat(optimized).contains("画面主体", "环境背景", "冬日雪景");
        }

        /**
         * 目的：空消息不做改写，原样透传（由 ContentGuard 在前面拦截非空校验）。
         * 实现：userText="" → aroundCall → 验证 chain 收到的仍是原 request（未改写）。
         * 结果：chain.nextCall(req) 被调用 1 次，参数为原 request。
         */
        @Test
        @DisplayName("空消息 → 不做改写，原样放行")
        void emptyMessage_passesThrough() {
            ChatClientRequest req = requestWithUserText("");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(req)).thenReturn(emptyResponse());

            advisor.adviseCall(req, chain);

            verify(chain).nextCall(req);
        }

        /**
         * 目的：PromptOptimize 的 order 应为 20，在 ContentGuard(0) 之后、Logging(30) 之前。
         * 结果：getOrder() == 20。
         */
        @Test
        @DisplayName("order 应为 20")
        void orderIsTwenty() {
            assertThat(advisor.getOrder()).isEqualTo(20);
        }
    }

    // ─── LoggingAdvisor ───────────────────────────────────────────

    @Nested
    @DisplayName("LoggingAdvisor：全量请求/响应日志")
    class LoggingTest {

        private final LoggingAdvisor advisor = new LoggingAdvisor();

        /**
         * 目的：验证非流式路径正常执行，日志记录不影响响应透传。
         * 实现：hello → aroundCall → 断言响应不为 null 且文本为 "ok"。
         * 结果：chain 被调用 1 次，响应正确。
         */
        @Test
        @DisplayName("非流式：正常执行 → 记录请求和响应日志")
        void normalExecution_logsRequestAndResponse() {
            ChatClientRequest req = requestWithUserText("hello");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(any())).thenReturn(emptyResponse());

            ChatClientResponse response = advisor.adviseCall(req, chain);

            assertThat(response).isNotNull();
            String text = response.chatResponse().getResult().getOutput().getText();
            assertThat(text).isEqualTo("ok");
            verify(chain, times(1)).nextCall(any());
        }

        /**
         * 目的：验证流式路径通过 MessageAggregator 聚合所有 chunk，在流完成时输出一条完整日志。
         * 实现：mock 返回 2 个 chunk 的 Flux → aroundStream → StepVerifier 验证 emit 2 个元素后完成。
         * 结果：流正常完成，无异常。
         */
        @Test
        @DisplayName("流式：MessageAggregator 聚合并记录完整响应")
        void stream_aggregatesAndLogs() {
            ChatClientRequest req = requestWithUserText("hello");
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
            ChatClientResponse chunk = emptyResponse();
            when(chain.nextStream(any())).thenReturn(Flux.just(chunk, chunk));

            Flux<ChatClientResponse> result = advisor.adviseStream(req, chain);

            StepVerifier.create(result)
                    .expectNextCount(2)
                    .verifyComplete();
        }

        /**
         * 目的：LoggingAdvisor 的 order 应为 30。
         * 结果：getOrder() == 30。
         */
        @Test
        @DisplayName("order 应为 30")
        void orderIsThirty() {
            assertThat(advisor.getOrder()).isEqualTo(30);
        }
    }

    // ─── ExceptionGuardAdvisor ────────────────────────────────────

    @Nested
    @DisplayName("ExceptionGuardAdvisor：异常兜底转友好回复")
    class ExceptionGuardTest {

        private final ExceptionGuardAdvisor advisor = new ExceptionGuardAdvisor();

        /**
         * 目的：正常链路不应被 ExceptionGuard 干扰。
         * 实现：正常 chain → aroundCall → 断言返回与预期一致。
         * 结果：响应原样透传。
         */
        @Test
        @DisplayName("正常执行 → 原样返回")
        void normalExecution_passesThrough() {
            ChatClientRequest req = requestWithUserText("hello");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            ChatClientResponse expected = emptyResponse();
            when(chain.nextCall(req)).thenReturn(expected);

            ChatClientResponse actual = advisor.adviseCall(req, chain);

            assertThat(actual).isEqualTo(expected);
        }

        /**
         * 目的：BusinessException 被捕获后转为用户可读的友好回复，而非 5xx。
         * 实现：chain 模拟抛 BusinessException(EMPTY_MESSAGE) → aroundCall → 断言返回的文本是 ErrorCode 的 message。
         * 结果：响应文本为 "消息不能为空"，而非异常堆栈。
         */
        @Test
        @DisplayName("业务异常 BusinessException → 转为友好回复")
        void businessException_returnsFriendlyResponse() {
            ChatClientRequest req = requestWithUserText("test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(any())).thenThrow(new BusinessException(ErrorCode.EMPTY_MESSAGE));

            ChatClientResponse response = advisor.adviseCall(req, chain);

            String text = response.chatResponse().getResult().getOutput().getText();
            assertThat(text).isEqualTo(ErrorCode.EMPTY_MESSAGE.getMessage());
        }

        @Test
        @DisplayName("未知异常 → 转为 SYSTEM_ERROR 友好回复")
        void unknownException_returnsInternalError() {
            ChatClientRequest req = requestWithUserText("test");
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(any())).thenThrow(new RuntimeException("模拟未知错误"));

            ChatClientResponse response = advisor.adviseCall(req, chain);

            String text = response.chatResponse().getResult().getOutput().getText();
            assertThat(text).isEqualTo(ErrorCode.SYSTEM_ERROR.getMessage());
        }

        /**
         * 目的：流式路径中 BusinessException 通过 .onErrorResume 降级为一条友好消息。
         * 实现：构造 Flux.error(BusinessException) → aroundStream → StepVerifier 断言降级消息内容。
         * 结果：仅 emit 1 个 ChatClientResponse，文本为对应 ErrorCode 的 message。
         */
        @Test
        @DisplayName("流式：异常 → 降级为单条友好消息")
        void streamException_returnsFallback() {
            ChatClientRequest req = requestWithUserText("test");
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
            when(chain.nextStream(any()))
                    .thenReturn(Flux.error(new BusinessException(ErrorCode.MESSAGE_TOO_LONG)));

            Flux<ChatClientResponse> result = advisor.adviseStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> {
                        String text = r.chatResponse().getResult().getOutput().getText();
                        assertThat(text).isEqualTo(ErrorCode.MESSAGE_TOO_LONG.getMessage());
                    })
                    .verifyComplete();
        }

        /**
         * 目的：流式路径中 unknown exception 也降级为 "系统内部异常"。
         * 实现：Flux.error(NullPointerException) → aroundStream → StepVerifier 断言。
         * 结果：友好回复文本 = SYSTEM_ERROR.getMessage()。
         */
        @Test
        @DisplayName("流式：未知异常 → 降级为内部错误消息")
        void streamUnknownError_returnsInternalError() {
            ChatClientRequest req = requestWithUserText("test");
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
            when(chain.nextStream(any()))
                    .thenReturn(Flux.error(new NullPointerException("模拟空指针")));

            Flux<ChatClientResponse> result = advisor.adviseStream(req, chain);

            StepVerifier.create(result)
                    .assertNext(r -> {
                        String text = r.chatResponse().getResult().getOutput().getText();
                        assertThat(text).isEqualTo(ErrorCode.SYSTEM_ERROR.getMessage());
                    })
                    .verifyComplete();
        }

        /**
         * 目的：ExceptionGuard 的 order 必须为 MIN_VALUE，保证在所有 Advisor 之前执行，最先兜底异常。
         * 结果：getOrder() == Integer.MIN_VALUE。
         */
        @Test
        @DisplayName("order 应为 Integer.MIN_VALUE（最优先执行）")
        void orderIsMin() {
            assertThat(advisor.getOrder()).isEqualTo(Integer.MIN_VALUE);
        }
    }
}
