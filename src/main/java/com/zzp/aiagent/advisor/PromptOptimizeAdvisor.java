package com.zzp.aiagent.advisor;

import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * Prompt 优化增强器 —— 将口语化描述扩写为专业生图 prompt。
 *
 * <h3>职责</h3>
 * 前置：改写 userText，将 "一张雪景" 扩展为包含主体/环境/光效/风格/画质的完整 prompt。
 * 后置：将优化后的 prompt 写入 adviseContext 供下游 Advisor 读取。
 *
 */
public class PromptOptimizeAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    /** 生图 prompt 模板——可扩展为调用轻量模型动态生成 */
    private static final String OPTIMIZE_TEMPLATE =
            "请基于用户的图片需求描述，生成一个优化的图片生成提示词(prompt)，包含以下要素：" +
            "画面主体、环境背景、光线氛围、艺术风格、画质描述。" +
            "用户需求：%s";

    /** adviseContext 中共享优化 prompt 的 key */
    public static final String KEY_OPTIMIZED_PROMPT = "optimizedPrompt";
    /** adviseContext 中共享原始用户输入的 key —— 供下游 LoggingAdvisor 记录 */
    public static final String KEY_ORIGINAL_INPUT = "originalUserText";

    /**
     * 非流式：前置改写 Prompt → 调用下游 → 后置写入共享上下文。
     */
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedRequest enhanced = enhance(request);
        AdvisedResponse response = chain.nextAroundCall(enhanced);
        return attachOptimizedContext(response, enhanced.userText());
    }

    /**
     * 流式：前置改写 → 调用下游（返回 Flux） → 对每个 AdvisedResponse 写入共享上下文。
     * 用 {@link Flux#map} 而非 doOnNext，因为需要修改 AdvisedResponse 本身。
     */
    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        AdvisedRequest enhanced = enhance(request);
        return chain.nextAroundStream(enhanced)
                .map(r -> attachOptimizedContext(r, enhanced.userText()));
    }

    /**
     * 前置增强：将原始输入存入 adviseContext（供下游日志记录），
     * 再用模板将口语描述扩写为专业生图 prompt。
     * 空消息直接原样放行——不做多余处理，交给 ContentGuard 提前拦截。
     */
    private AdvisedRequest enhance(AdvisedRequest request) {
        String original = request.userText();
        if (original == null || original.isBlank()) {
            return request;
        }
        String optimized = String.format(OPTIMIZE_TEMPLATE, original);
        Map<String, Object> ctx = new HashMap<>(request.adviseContext());
        ctx.put(KEY_ORIGINAL_INPUT, original);
        return AdvisedRequest.from(request)
                .userText(optimized)
                .adviseContext(ctx)
                .build();
    }

    /**
     * 后置：将优化后的 prompt 写入 adviseContext，供下游 Advisor 观测或日志记录。
     * 必须复制原有 Map 再 put，不可直接修改——M6 的 Record 语义要求不可变。
     */
    private AdvisedResponse attachOptimizedContext(AdvisedResponse response, String optimized) {
        Map<String, Object> ctx = new HashMap<>(response.adviseContext());
        ctx.put(KEY_OPTIMIZED_PROMPT, optimized);
        return AdvisedResponse.from(response).adviseContext(ctx).build();
    }

    @Override
    public String getName() {
        return "PromptOptimize";
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
