package com.zzp.aiagent.advisor;

import com.zzp.aiagent.exception.ContentSafetyException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.exception.InvalidInputException;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <h3>职责</h3>
 * 在 Prompt 到达大模型之前做输入校验，不合法直接抛异常短路后续所有 Advisor。
 * 相当于 AOP 中 Order=0 的前置通知。
 *
 * <h3>校验顺序</h3>
 * <ol>
 *   <li>空值检查：null 或 blank → EMPTY_MESSAGE(1001)</li>
 *   <li>长度检查：超过 2000 字符 → MESSAGE_TOO_LONG(1002)</li>
 *   <li>敏感词检查：命中关键词列表 → CONTENT_BLOCKED(2001)</li>
 * </ol>
 *
 * <h3>短路机制</h3>
 * 通过抛异常实现，不 return 任何值。异常沿调用栈向上，
 * 被 {@link ExceptionGuardAdvisor} (order=MAX) 或 {@link GlobalExceptionHandler} 捕获转友好回复。
 *
 * <h3>同时支持流式/非流式</h3>
 * 两个重载方法共享同一个 {@link #validate(AdvisedRequest)}，校验逻辑一致。
 *
 *
 * @see ExceptionGuardAdvisor
 */
public class ContentGuardAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** 敏感词列表——命中任一词即拦截。可扩展为从配置中心动态加载。 */
    private static final List<String> BLOCKED_KEYWORDS = List.of("暴力", "色情", "政治敏感");

    /**
     * 非流式调用入口。
     *
     * @param request 包含 userText/messages/adviseContext 的请求封装
     * @param chain   后续 Advisor 调用链
     * @return 下游 Advisor 的响应
     * @throws InvalidInputException  输入不合法
     * @throws ContentSafetyException 命中敏感词
     */
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        validate(request);
        return chain.nextAroundCall(request);
    }

    /**
     * 流式调用入口——校验逻辑与非流式完全一致。
     * 流式场景下抛异常，由 Reactor 的错误信号传递，
     * 最终被 ExceptionGuardAdvisor.aroundStream 的 onErrorResume 兜住。
     */
    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        validate(request);
        return chain.nextAroundStream(request);
    }

    /**
     * 三层校验，按开销从小到大排列：
     * null/空判断（O(1)）→ 长度判断（O(1)）→ 敏感词遍历（O(n)）。
     * 这样大多数非法请求在前两步就被拦截，减少不必要的字符串匹配开销。
     */
    private void validate(AdvisedRequest request) {
        String message = request.userText();

        // 第一层：空值——最常见的非法输入
        if (message == null || message.isBlank()) {
            throw new InvalidInputException(ErrorCode.EMPTY_MESSAGE);
        }
        // 第二层：长度——防止 token 超限导致后续调用失败
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new InvalidInputException(ErrorCode.MESSAGE_TOO_LONG,
                    "当前长度 " + message.length() + "，最大允许 " + MAX_MESSAGE_LENGTH);
        }
        // 第三层：敏感词——开销最大，放在最后
        String lower = message.toLowerCase();
        for (String keyword : BLOCKED_KEYWORDS) {
            if (lower.contains(keyword)) {
                throw new ContentSafetyException("消息包含违规词汇: " + keyword);
            }
        }
    }

    @Override
    public String getName() {
        return "ContentGuard";
    }

    /**
     * Order=0：在所有 Advisor 中第一个执行。
     * 非法请求不应消耗下游资源（Memory 恢复、Prompt 改写、大模型调用），
     * 必须在最外层拦截。
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
