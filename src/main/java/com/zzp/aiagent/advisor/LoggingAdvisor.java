package com.zzp.aiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 全量请求/响应日志记录器。
 *
 * <h3>记录内容</h3>
 * <ul>
 *   <li>请求时间、会话 ID、调用方式（流式/非流式）</li>
 *   <li>用户原始输入（从 adviseContext 中读取，由 PromptOptimizeAdvisor 写入）</li>
 *   <li>实际发往模型的 Prompt（可能已被上游 Advisor 改写）</li>
 *   <li>AI 完整回复文本及字符数</li>
 *   <li>调用耗时（ms）</li>
 * </ul>
 *
 * <h3>流式处理</h3>
 * 通过 {@link MessageAggregator#aggregateAdvisedResponse} 聚合流式 token，
 * 在流完成时输出完整回复日志。聚合不阻塞 SSE 推送。
 */
@Slf4j
public class LoggingAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private static final String KEY_START_TIME = "logging.startTime";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final MessageAggregator aggregator = new MessageAggregator();

    // ── 非流式 ──────────────────────────────────────────────────

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        Instant start = Instant.now();
        String chatId = (String) request.adviseContext().get("chatId");
        String originalInput = (String) request.adviseContext().get(PromptOptimizeAdvisor.KEY_ORIGINAL_INPUT);
        String actualPrompt = request.userText();

        logRequest(chatId, "非流式", originalInput, actualPrompt);

        AdvisedRequest req = withStartTime(request, start);
        AdvisedResponse response = chain.nextAroundCall(req);

        logResponse(chatId, start, response);
        return response;
    }

    // ── 流式 ────────────────────────────────────────────────────

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        Instant start = Instant.now();
        String chatId = (String) request.adviseContext().get("chatId");
        String originalInput = (String) request.adviseContext().get(PromptOptimizeAdvisor.KEY_ORIGINAL_INPUT);
        String actualPrompt = request.userText();

        logRequest(chatId, "流式(SSE)", originalInput, actualPrompt);

        AdvisedRequest req = withStartTime(request, start);
        Flux<AdvisedResponse> stream = chain.nextAroundStream(req);

        return aggregator.aggregateAdvisedResponse(stream, aggregated ->
                logResponse(chatId, start, aggregated));
    }

    // ── 日志输出 ────────────────────────────────────────────────

    private void logRequest(String chatId, String mode, String originalInput, String actualPrompt) {
        String time = TIME_FMT.format(Instant.now().atZone(ZoneId.systemDefault()));
        log.info("[AI-请求] 时间={} chatId={} 方式={}", time, chatId, mode);
        if (originalInput != null && !originalInput.equals(actualPrompt)) {
            log.info("[AI-请求] chatId={} 原始输入(length={}): {}", chatId,
                    originalInput.length(), originalInput);
        }
        log.info("[AI-请求] chatId={} 实际Prompt(length={}): {}", chatId,
                actualPrompt != null ? actualPrompt.length() : 0, actualPrompt);
    }

    private void logResponse(String chatId, Instant start, AdvisedResponse response) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        String responseText = safeGetText(response);
        log.info("[AI-响应] chatId={} 耗时={}ms 回复字符数={}",
                chatId, elapsed, responseText != null ? responseText.length() : 0);
        log.info("[AI-响应] chatId={} 回复内容: {}", chatId, responseText);
    }

    // ── 工具方法 ────────────────────────────────────────────────

    private AdvisedRequest withStartTime(AdvisedRequest request, Instant start) {
        Map<String, Object> ctx = new HashMap<>(request.adviseContext());
        ctx.put(KEY_START_TIME, start);
        return AdvisedRequest.from(request).adviseContext(ctx).build();
    }

    private static String safeGetText(AdvisedResponse response) {
        try {
            return response.response().getResult().getOutput().getText();
        } catch (Exception e) {
            return "<无法提取回复文本>";
        }
    }

    @Override
    public String getName() {
        return "Logging";
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
