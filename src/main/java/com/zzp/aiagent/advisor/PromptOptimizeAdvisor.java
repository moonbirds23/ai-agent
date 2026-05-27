package com.zzp.aiagent.advisor;

import com.zzp.aiagent.common.PromptTemplate;
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
 * 前置改写userText为专业生图prompt，后置将改写结果写入adviseContext供下游读取。
 */
public class PromptOptimizeAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private final PromptTemplate promptTemplate;

    /** adviseContext 中共享优化 prompt 的 key */
    public static final String KEY_OPTIMIZED_PROMPT = "optimizedPrompt";
    /** adviseContext 中共享原始用户输入的 key —— 供下游 LoggingAdvisor 记录 */
    public static final String KEY_ORIGINAL_INPUT = "originalUserText";

    public PromptOptimizeAdvisor(PromptTemplate promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedRequest enhanced = enhance(request);
        AdvisedResponse response = chain.nextAroundCall(enhanced);
        return attachOptimizedContext(response, enhanced.userText());
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        AdvisedRequest enhanced = enhance(request);
        return chain.nextAroundStream(enhanced)
                .map(r -> attachOptimizedContext(r, enhanced.userText()));
    }

    private AdvisedRequest enhance(AdvisedRequest request) {
        String original = request.userText();
        if (original == null || original.isBlank()) {
            return request;
        }
        String optimized = promptTemplate.render("default", "optimize", "userInput", original);
        // adviseContext是unmodifiableMap，必须复制→修改→重建（Record不可变语义）
        Map<String, Object> ctx = new HashMap<>(request.adviseContext());
        ctx.put(KEY_ORIGINAL_INPUT, original);               // 原始输入供LoggingAdvisor记录
        return AdvisedRequest.from(request)
                .userText(optimized)                         // 替换为增强后的prompt，LLM实际收到的是这个
                .adviseContext(ctx)
                .build();
    }

    private AdvisedResponse attachOptimizedContext(AdvisedResponse response, String optimized) {
        Map<String, Object> ctx = new HashMap<>(response.adviseContext());
        ctx.put(KEY_OPTIMIZED_PROMPT, optimized);            // 供下游Advisor观测改写结果
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
