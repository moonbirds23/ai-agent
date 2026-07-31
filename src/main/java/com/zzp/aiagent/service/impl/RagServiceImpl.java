package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.domain.rag.RagProperties;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagRewriteResult;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.domain.rag.RagTrace;
import com.zzp.aiagent.domain.rag.RagContext;
import com.zzp.aiagent.service.HybridGalleryRetriever;
import com.zzp.aiagent.service.RagContextPacker;
import com.zzp.aiagent.service.RagQueryRewriteService;
import com.zzp.aiagent.service.RagReranker;
import com.zzp.aiagent.service.RagService;
import com.zzp.aiagent.service.RagTraceService;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.observability.AgentObservationKeys;
import com.zzp.aiagent.observability.AgentObservationNames;
import com.zzp.aiagent.observability.AgentTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@Profile("!test")
@Slf4j
public class RagServiceImpl implements RagService {

    private final ExplicitReferenceResolver explicitResolver;
    private final StyleTemplateService templateService;
    private final RagProperties ragProperties;

    // Enhance components
    private final RagQueryRewriteService rewriteService;
    private final HybridGalleryRetriever hybridRetriever;
    private final RagReranker reranker;
    private final RagContextPacker packer;
    private final RagTraceService traceService;
    private final ChatMemory chatMemory;
    private final AgentTelemetry telemetry;

    public RagServiceImpl(ExplicitReferenceResolver explicitResolver,
                          StyleTemplateService templateService,
                          RagProperties ragProperties,
                          ObjectProvider<RagQueryRewriteService> rewriteProvider,
                          ObjectProvider<HybridGalleryRetriever> hybridProvider,
                          ObjectProvider<RagReranker> rerankerProvider,
                          ObjectProvider<RagContextPacker> packerProvider,
                          ObjectProvider<RagTraceService> traceProvider,
                          ObjectProvider<ChatMemory> chatMemoryProvider,
                          ObjectProvider<AgentTelemetry> telemetryProvider) {
        this.explicitResolver = explicitResolver;
        this.templateService = templateService;
        this.ragProperties = ragProperties;
        this.rewriteService = rewriteProvider.getIfAvailable();
        this.hybridRetriever = hybridProvider.getIfAvailable();
        this.reranker = rerankerProvider.getIfAvailable();
        this.packer = packerProvider.getIfAvailable();
        this.traceService = traceProvider.getIfAvailable();
        this.chatMemory = chatMemoryProvider.getIfAvailable();
        this.telemetry = telemetryProvider.getIfAvailable();
    }

    @Override
    public RagContext buildContext(ChatRequest request) {
        RagObservationState state = new RagObservationState();
        AgentTelemetry.AgentObservation observation = startObservation(AgentObservationNames.RAG);
        if (observation != null) {
            observation.highCardinality(AgentObservationKeys.High.CHAT_ID, request.chatId())
                    .lowCardinality(AgentObservationKeys.Low.RAG_REFERENCE_MODE,
                            request.referenceMode() != null ? request.referenceMode() : "default");
        }
        try (var ignored = observation != null ? observation.openScope() : null) {
            RagContext context = buildContext(request, state);
            finishRagObservation(observation, state, "success");
            return context;
        } catch (RuntimeException error) {
            if (observation != null) {
                observation.error(error);
            }
            finishRagObservation(observation, state, "error");
            throw error;
        } finally {
            if (observation != null) {
                observation.stop();
            }
        }
    }

    private RagContext buildContext(ChatRequest request, RagObservationState state) {
        long start = System.currentTimeMillis();
        RagContext ctx = RagContext.empty();
        String originalQuery = request.message();

        // Per-stage timing accumulators
        long rewriteLatency = 0;
        long retrieveLatency = 0;
        long rerankLatency = 0;
        long packLatency = 0;
        String retrievalPath = "UNKNOWN";
        List<Long> candidateIds = List.of();
        List<Long> selectedIds = List.of();

        // Global RAG toggle: when disabled, only Layer 1 (explicit user-specified refs) is honored
        if (!ragProperties.enabled()) {
            state.path = "DISABLED";
            if (request.referencePictureIds() != null && !request.referencePictureIds().isEmpty()) {
                List<RagContext.ReferencePicture> refs = explicitResolver.resolve(request.referencePictureIds());
                for (RagContext.ReferencePicture ref : refs) {
                    ctx.addExplicit(ref);
                }
            }
            log.info("[RagService] RAG 已全局关闭，仅保留显式参考图: explicit={}", ctx.getExplicitReferences().size());
            recordTrace(request, ctx, start, originalQuery, rewriteLatency, retrieveLatency,
                    rerankLatency, packLatency, "DISABLED", candidateIds, selectedIds);
            return ctx;
        }

        // Layer 1: 明确参考图（最高优先级）—— 两个路径共用
        if (request.referencePictureIds() != null && !request.referencePictureIds().isEmpty()) {
            List<RagContext.ReferencePicture> refs = explicitResolver.resolve(request.referencePictureIds());
            for (RagContext.ReferencePicture ref : refs) {
                ctx.addExplicit(ref);
            }
        }

        // Layer 2: RAG 增强检索 (with per-stage timing)
        if ((request.useGalleryRag() == null || request.useGalleryRag())
                && originalQuery != null && !originalQuery.isBlank()) {
            state.attempted = true;
            var layer2Result = layer2Enhance(ctx, request, originalQuery, state);
            rewriteLatency = layer2Result.rewriteLatencyMs;
            retrieveLatency = layer2Result.retrieveLatencyMs;
            rerankLatency = layer2Result.rerankLatencyMs;
            retrievalPath = layer2Result.retrievalPath;
            candidateIds = layer2Result.candidateIds;
            selectedIds = layer2Result.selectedIds;
            state.path = retrievalPath;
            state.candidateCount = layer2Result.candidateCount;
            state.selectedCount = layer2Result.selectedCount;
        }

        // Layer 3: 风格模板降级兜底
        layer3Template(ctx, request);

        // Pack and truncate context (referenceMode trimming + maxContextChars limit)
        if (packer != null && !ctx.isEmpty()) {
            long packStart = System.currentTimeMillis();
            RagSearchCriteria criteria = (RagSearchCriteria) ctx.getTrace().get("criteria");
            ctx.withPacked(observeStage(AgentObservationNames.RAG_PACK, () -> packer.pack(ctx, criteria)));
            packLatency = System.currentTimeMillis() - packStart;
        }

        // Trace
        recordTrace(request, ctx, start, originalQuery, rewriteLatency, retrieveLatency,
                rerankLatency, packLatency, retrievalPath, candidateIds, selectedIds);

        log.info("[RagService] 上下文构建完成: explicit={}, retrieved={}, template={}",
                ctx.getExplicitReferences().size(),
                ctx.getRetrievedReferences().size(),
                ctx.getStyleTemplate() != null ? ctx.getStyleTemplate().code() : "none");
        return ctx;
    }

    private void recordTrace(ChatRequest request, RagContext ctx, long start, String originalQuery,
                             long rewriteLatency, long retrieveLatency, long rerankLatency,
                             long packLatency, String retrievalPath,
                             List<Long> candidateIds, List<Long> selectedIds) {
        if (traceService != null) {
            long latency = System.currentTimeMillis() - start;
            String rewritten = (String) ctx.getTrace().get("rewrittenQuery");
            String templateCode = ctx.getStyleTemplate() != null ? ctx.getStyleTemplate().code() : null;
            int candidateCount = collectionSize(ctx.getTrace().get("candidates"), candidateIds.size());
            int selectedCount = collectionSize(ctx.getTrace().get("selected"), selectedIds.size());
            traceService.record(new RagTrace(
                    request.chatId(), null, originalQuery, rewritten,
                    ctx.getTrace().get("criteria"),
                    ctx.getTrace().get("candidates"),
                    ctx.getTrace().get("selected"),
                    templateCode, null, latency, LocalDateTime.now(),
                    rewriteLatency, retrieveLatency, rerankLatency, packLatency,
                    retrievalPath, candidateIds, selectedIds,
                    candidateCount, selectedCount));
        }
    }

    // ── Layer 2: 增强路径 ────────────────────────────────────────────

    /**
     * Per-stage timing and ID data returned from Layer 2 operations.
     */
    private record Layer2Timing(long rewriteLatencyMs, long retrieveLatencyMs, long rerankLatencyMs,
                                String retrievalPath,
                                List<Long> candidateIds, List<Long> selectedIds,
                                int candidateCount, int selectedCount) {}

    private Layer2Timing layer2Enhance(RagContext ctx, ChatRequest request, String originalQuery,
                                       RagObservationState state) {
        // Phase 1: Query Rewrite
        long rewriteStart = System.currentTimeMillis();
        String conversationHistory = buildConversationHistory(request.chatId());
        RagRewriteResult rewrite = observeStage(AgentObservationNames.RAG_REWRITE,
                () -> rewriteService.rewrite(originalQuery, conversationHistory));
        long rewriteLatency = System.currentTimeMillis() - rewriteStart;
        ctx.putTrace("rewrittenQuery", rewrite.searchQuery());
        ctx.putTrace("rewriteLatencyMs", rewriteLatency);

        String effectiveMode = resolveReferenceMode(rewrite, request);
        ctx.putTrace("resolvedReferenceMode", effectiveMode);

        RagSearchCriteria criteria = new RagSearchCriteria(
                rewrite.searchQuery(),
                rewrite.category(),
                rewrite.tags(),
                rewrite.styleHints(),
                rewrite.colorHints(),
                rewrite.compositionHints(),
                ragProperties.retrieveFavoritesOnly(),
                effectiveMode,
                ragProperties.topK() * 4,  // oversample for rerank
                ragProperties.topK(),
                ragProperties.minScore());
        ctx.putTrace("criteria", criteria);

        // Phase 2: Hybrid Retrieval
        long retrieveStart = System.currentTimeMillis();
        List<RagCandidate> candidates = observeStage(AgentObservationNames.RAG_RETRIEVE,
                () -> hybridRetriever.retrieve(criteria));
        long retrieveLatency = System.currentTimeMillis() - retrieveStart;
        ctx.putTrace("candidates", candidates);
        ctx.putTrace("retrieveLatencyMs", retrieveLatency);

        // Classify retrieval path
        String retrievalPath;
        if (candidates.isEmpty()) {
            retrievalPath = "EMPTY";
        } else {
            boolean hasVector = candidates.stream().anyMatch(c -> c.vectorScore() > 0);
            boolean hasKeywordFallback = candidates.stream()
                    .anyMatch(c -> c.reasons().contains("关键词回退"));
            if (hasKeywordFallback) {
                retrievalPath = "KEYWORD_FALLBACK";
            } else {
                retrievalPath = "VECTOR";
            }
        }
        state.path = retrievalPath;
        state.candidateCount = candidates.size();

        // Phase 3: Rerank
        long rerankStart = System.currentTimeMillis();
        List<RagCandidate> selected = observeStage(AgentObservationNames.RAG_RERANK,
                () -> reranker.rerank(candidates, criteria));
        state.selectedCount = selected.size();
        long rerankLatency = System.currentTimeMillis() - rerankStart;
        if (telemetry != null) {
            telemetry.record("agent.rag.rerank.duration", Duration.ofMillis(rerankLatency),
                    AgentObservationKeys.Low.RAG_PATH, retrievalPath);
        }
        ctx.putTrace("selected", selected);
        ctx.putTrace("rerankLatencyMs", rerankLatency);

        // Collect picture IDs
        List<Long> candidateIds = candidates.stream()
                .map(c -> c.picture().id())
                .filter(id -> id != null)
                .toList();
        List<Long> selectedIds = selected.stream()
                .map(c -> c.picture().id())
                .filter(id -> id != null)
                .toList();

        for (RagCandidate c : selected) {
            if (c.picture() != null) {
                ctx.addRetrieved(new RagContext.ReferencePicture(c.picture(), c.profile()));
            }
        }

        // Build trace summary for debug
        List<Map<String, Object>> selectedSummary = new ArrayList<>();
        for (RagCandidate c : selected) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pictureId", c.picture().id());
            item.put("name", c.picture().name());
            item.put("vectorScore", round2(c.vectorScore()));
            item.put("keywordScore", round2(c.keywordScore()));
            item.put("metadataScore", round2(c.metadataScore()));
            item.put("finalScore", round2(c.finalScore()));
            item.put("reasons", c.reasons());
            selectedSummary.add(item);
        }
        ctx.putTrace("selectedSummary", selectedSummary);

        return new Layer2Timing(rewriteLatency, retrieveLatency, rerankLatency,
                retrievalPath, candidateIds, selectedIds, candidates.size(), selected.size());
    }

    // ── Layer 3 ──────────────────────────────────────────────────────

    private void layer3Template(RagContext ctx, ChatRequest request) {
        if (!ctx.getExplicitReferences().isEmpty() || !ctx.getRetrievedReferences().isEmpty()) {
            return;
        }
        String query = request.message();
        if (query == null || query.isBlank()) return;

        if (request.styleTemplateCode() != null && !request.styleTemplateCode().isBlank()) {
            templateService.getByCode(request.styleTemplateCode())
                    .ifPresent(ctx::withTemplate);
        } else {
            templateService.match(query).ifPresent(ctx::withTemplate);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String buildConversationHistory(String conversationId) {
        if (chatMemory == null || conversationId == null) return "";
        try {
            List<Message> messages = chatMemory.get(conversationId);
            if (messages == null || messages.isEmpty()) return "";
            // Only process the last 20 messages to keep history compact
            int start = Math.max(0, messages.size() - 20);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < messages.size(); i++) {
                Message msg = messages.get(i);
                String text = msg.getText();
                if (text == null || text.isBlank()) continue;
                if (!sb.isEmpty()) sb.append("\n");
                String roleLabel = "USER".equals(msg.getMessageType().name()) ? "用户" : "助手";
                // Truncate to avoid polluting the rewrite prompt
                String truncated = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                sb.append(roleLabel).append(": ").append(truncated);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[RagService] 获取对话历史失败 conversationId={}: {}", conversationId, e.getMessage());
            return "";
        }
    }

    private String resolveReferenceMode(RagRewriteResult rewrite, ChatRequest request) {
        if (rewrite.referenceMode() != null) return rewrite.referenceMode();
        if (request.referenceMode() != null && !request.referenceMode().isBlank())
            return request.referenceMode();
        return null;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static int collectionSize(Object value, int fallback) {
        return value instanceof java.util.Collection<?> collection ? collection.size() : fallback;
    }

    private AgentTelemetry.AgentObservation startObservation(String name) {
        return telemetry != null ? telemetry.start(name) : null;
    }

    private <T> T observeStage(String name, Supplier<T> operation) {
        AgentTelemetry.AgentObservation observation = startObservation(name);
        try (var ignored = observation != null ? observation.openScope() : null) {
            T result = operation.get();
            if (observation != null) {
                observation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "success");
            }
            return result;
        } catch (RuntimeException error) {
            if (observation != null) {
                observation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error").error(error);
            }
            throw error;
        } finally {
            if (observation != null) {
                observation.stop();
            }
        }
    }

    private void finishRagObservation(AgentTelemetry.AgentObservation observation,
                                      RagObservationState state,
                                      String outcome) {
        String path = state.path != null ? state.path : (state.attempted ? "UNKNOWN" : "NOT_TRIGGERED");
        boolean empty = state.attempted && "success".equals(outcome) && state.candidateCount == 0;
        if (observation != null) {
            observation.lowCardinality(AgentObservationKeys.Low.OUTCOME, outcome)
                    .lowCardinality(AgentObservationKeys.Low.RAG_PATH, path)
                    .lowCardinality(AgentObservationKeys.Low.RAG_EMPTY, empty)
                    .highCardinality(AgentObservationKeys.High.RAG_CANDIDATE_COUNT, state.candidateCount)
                    .highCardinality(AgentObservationKeys.High.RAG_SELECTED_COUNT, state.selectedCount);
        }
        if (telemetry != null && state.attempted) {
            telemetry.increment("agent.rag.requests",
                    AgentObservationKeys.Low.RAG_PATH, path,
                    AgentObservationKeys.Low.RAG_EMPTY, Boolean.toString(empty),
                    AgentObservationKeys.Low.OUTCOME, outcome);
            telemetry.recordAmount("agent.rag.candidates", state.candidateCount,
                    AgentObservationKeys.Low.RAG_PATH, path);
            telemetry.recordAmount("agent.rag.selected", state.selectedCount,
                    AgentObservationKeys.Low.RAG_PATH, path);
        }
    }

    private static final class RagObservationState {
        private boolean attempted;
        private String path;
        private int candidateCount;
        private int selectedCount;
    }
}
