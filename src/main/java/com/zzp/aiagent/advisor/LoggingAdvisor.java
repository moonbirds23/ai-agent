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
 * 全量日志记录：原始输入、改写后Prompt、回复内容、耗时。
 * 流式通过MessageAggregator聚合token，聚合不阻塞SSE推送，在流完成时输出完整日志。
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
