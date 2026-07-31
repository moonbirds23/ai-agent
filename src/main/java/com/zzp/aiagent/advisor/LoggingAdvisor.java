package com.zzp.aiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class LoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final String KEY_START_TIME = "logging.startTime";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final ChatClientMessageAggregator aggregator = new ChatClientMessageAggregator();

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Instant start = Instant.now();
        String chatId = (String) request.context().get("chatId");
        String actualPrompt = getUserText(request);
        logRequest(chatId, "非流式", actualPrompt);

        ChatClientRequest req = withStartTime(request, start);
        ChatClientResponse response = chain.nextCall(req);

        logResponse(chatId, start, response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Instant start = Instant.now();
        String chatId = (String) request.context().get("chatId");
        String actualPrompt = getUserText(request);
        logRequest(chatId, "流式(SSE)", actualPrompt);

        ChatClientRequest req = withStartTime(request, start);
        Flux<ChatClientResponse> stream = chain.nextStream(req);

        return aggregator.aggregateChatClientResponse(stream, aggregated ->
                logResponse(chatId, start, aggregated));
    }

    private void logRequest(String chatId, String mode, String actualPrompt) {
        String time = TIME_FMT.format(Instant.now().atZone(ZoneId.systemDefault()));
        log.info("[AI-请求] 时间={} chatId={} 方式={} promptLength={}",
                time, chatId, mode, actualPrompt != null ? actualPrompt.length() : 0);
    }

    private void logResponse(String chatId, Instant start, ChatClientResponse response) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        String responseText = safeGetText(response);
        log.info("[AI-响应] chatId={} 耗时={}ms 回复字符数={}",
                chatId, elapsed, responseText != null ? responseText.length() : 0);
    }

    private ChatClientRequest withStartTime(ChatClientRequest request, Instant start) {
        Map<String, Object> ctx = new HashMap<>(request.context());
        ctx.put(KEY_START_TIME, start);
        return request.mutate().context(ctx).build();
    }

    private static String safeGetText(ChatClientResponse response) {
        try {
            return response.chatResponse().getResult().getOutput().getText();
        } catch (Exception e) {
            return "<无法提取回复文本>";
        }
    }

    private static String getUserText(ChatClientRequest request) {
        UserMessage userMsg = request.prompt().getUserMessage();
        return userMsg != null ? userMsg.getText() : "";
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
