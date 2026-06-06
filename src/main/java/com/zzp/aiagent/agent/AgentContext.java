package com.zzp.aiagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-turn execution context, keyed by {@code turnId}.
 * <p>
 * Uses a {@link ConcurrentMap} rather than {@code ThreadLocal} because
 * Reactor streaming pipelines may complete on a different thread than
 * the one that initiated the request.
 */
public final class AgentContext {

    private static final ConcurrentMap<String, AgentContext> STORE = new ConcurrentHashMap<>();

    private AgentState state;
    private int currentRound;
    private final List<RoundTrace> traces;
    private final long startedAt;
    private long finishedAt;
    private String errorMessage;

    private AgentContext() {
        this.state = AgentState.IDLE;
        this.currentRound = 0;
        this.traces = new ArrayList<>();
        this.startedAt = System.currentTimeMillis();
    }

    // ── lifecycle ──────────────────────────────────────────────────

    static void init(String turnId, AgentConfig config) {
        if (turnId == null || turnId.isBlank()) return;
        AgentContext ctx = new AgentContext();
        ctx.state = AgentState.RUNNING;
        STORE.put(turnId, ctx);
    }

    static void finish(String turnId) {
        if (turnId == null || turnId.isBlank()) return;
        AgentContext ctx = STORE.get(turnId);
        if (ctx != null && ctx.state == AgentState.RUNNING) {
            ctx.state = AgentState.FINISHED;
            ctx.finishedAt = System.currentTimeMillis();
        }
    }

    static void error(String turnId, String message) {
        if (turnId == null || turnId.isBlank()) return;
        AgentContext ctx = STORE.get(turnId);
        if (ctx != null) {
            ctx.state = AgentState.ERROR;
            ctx.errorMessage = message;
            ctx.finishedAt = System.currentTimeMillis();
        }
    }

    static void clear(String turnId) {
        if (turnId != null && !turnId.isBlank()) {
            STORE.remove(turnId);
        }
    }

    // ── round tracking ─────────────────────────────────────────────

    static int nextRound(String turnId) {
        if (turnId == null || turnId.isBlank()) return 0;
        AgentContext ctx = STORE.get(turnId);
        if (ctx == null) {
            return 0;
        }
        ctx.currentRound++;
        return ctx.currentRound;
    }

    static void addTrace(String turnId, RoundTrace trace) {
        if (turnId == null || turnId.isBlank()) return;
        AgentContext ctx = STORE.get(turnId);
        if (ctx != null && trace != null) {
            ctx.traces.add(trace);
        }
    }

    // ── snapshot ───────────────────────────────────────────────────

    public static Snapshot snapshot(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return new Snapshot(AgentState.IDLE, 0, 0, 0, List.of(), null);
        }
        AgentContext ctx = STORE.get(turnId);
        if (ctx == null) {
            return new Snapshot(AgentState.IDLE, 0, 0, 0, List.of(), null);
        }
        return new Snapshot(
                ctx.state,
                ctx.currentRound,
                ctx.startedAt,
                ctx.finishedAt,
                List.copyOf(ctx.traces),
                ctx.errorMessage
        );
    }

    // ── types ──────────────────────────────────────────────────────

    public record RoundTrace(int round, List<String> toolCalls, long durationMs) {
    }

    public record Snapshot(
            AgentState state,
            int totalRounds,
            long startedAt,
            long finishedAt,
            List<RoundTrace> traces,
            String errorMessage
    ) {
        public long elapsedMs() {
            return finishedAt > 0 ? finishedAt - startedAt : System.currentTimeMillis() - startedAt;
        }
    }
}
