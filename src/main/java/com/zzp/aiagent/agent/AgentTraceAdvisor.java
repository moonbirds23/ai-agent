package com.zzp.aiagent.agent;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.tool.CurrentImageContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Observability-only advisor that tracks rounds and enforces
 * the {@code maxToolCalls} limit.
 * <p>
 * Turn id is read from the request context (key {@code turnId}) so
 * that the {@link AgentContext} map can be addressed cross-thread.
 */
@Slf4j
public class AgentTraceAdvisor implements CallAdvisor, StreamAdvisor {

    private final AgentConfig config;

    public AgentTraceAdvisor(AgentConfig config) {
        this.config = config;
    }

    // ── Call (non-streaming) ────────────────────────────────────────

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String turnId = turnIdFrom(request);
        int round = AgentContext.nextRound(turnId);
        checkMaxRounds(round);

        long start = System.currentTimeMillis();
        try {
            ChatClientResponse response = chain.nextCall(request);
            recordRound(turnId, round, start);
            return response;
        } catch (Exception e) {
            recordRound(turnId, round, start);
            throw e;
        }
    }

    // ── Stream ──────────────────────────────────────────────────────

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String turnId = turnIdFrom(request);
        int round = AgentContext.nextRound(turnId);
        checkMaxRounds(round);

        long start = System.currentTimeMillis();
        return chain.nextStream(request)
                .doFinally(signal -> recordRound(turnId, round, start));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private void checkMaxRounds(int currentRound) {
        if (currentRound > config.maxToolCalls()) {
            throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                    "Agent 执行步数超限（最大 " + config.maxToolCalls() + " 步）");
        }
    }

    private static void recordRound(String turnId, int round, long start) {
        long duration = System.currentTimeMillis() - start;
        List<String> toolNames = new ArrayList<>();
        AgentContext.addTrace(turnId, new AgentContext.RoundTrace(round, toolNames, duration));
        log.debug("[AgentTrace] turnId={} round={} duration={}ms", turnId, round, duration);
    }

    private static String turnIdFrom(ChatClientRequest request) {
        Object value = request.context().get("turnId");
        return value instanceof String s ? s : null;
    }

    @Override
    public String getName() {
        return "AgentTrace";
    }

    @Override
    public int getOrder() {
        return 5;
    }
}
