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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public RagServiceImpl(ExplicitReferenceResolver explicitResolver,
                          StyleTemplateService templateService,
                          RagProperties ragProperties,
                          ObjectProvider<RagQueryRewriteService> rewriteProvider,
                          ObjectProvider<HybridGalleryRetriever> hybridProvider,
                          ObjectProvider<RagReranker> rerankerProvider,
                          ObjectProvider<RagContextPacker> packerProvider,
                          ObjectProvider<RagTraceService> traceProvider,
                          ObjectProvider<ChatMemory> chatMemoryProvider) {
        this.explicitResolver = explicitResolver;
        this.templateService = templateService;
        this.ragProperties = ragProperties;
        this.rewriteService = rewriteProvider.getIfAvailable();
        this.hybridRetriever = hybridProvider.getIfAvailable();
        this.reranker = rerankerProvider.getIfAvailable();
        this.packer = packerProvider.getIfAvailable();
        this.traceService = traceProvider.getIfAvailable();
        this.chatMemory = chatMemoryProvider.getIfAvailable();
    }

    @Override
    public RagContext buildContext(ChatRequest request) {
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
            var layer2Result = layer2Enhance(ctx, request, originalQuery);
            rewriteLatency = layer2Result.rewriteLatencyMs;
            retrieveLatency = layer2Result.retrieveLatencyMs;
            rerankLatency = layer2Result.rerankLatencyMs;
            retrievalPath = layer2Result.retrievalPath;
            candidateIds = layer2Result.candidateIds;
            selectedIds = layer2Result.selectedIds;
        }

        // Layer 3: 风格模板降级兜底
        layer3Template(ctx, request);

        // Pack and truncate context (referenceMode trimming + maxContextChars limit)
        if (packer != null && !ctx.isEmpty()) {
            long packStart = System.currentTimeMillis();
            RagSearchCriteria criteria = (RagSearchCriteria) ctx.getTrace().get("criteria");
            ctx.withPacked(packer.pack(ctx, criteria));
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
            traceService.record(new RagTrace(
                    request.chatId(), null, originalQuery, rewritten,
                    ctx.getTrace().get("criteria"),
                    ctx.getTrace().get("candidates"),
                    ctx.getTrace().get("selected"),
                    templateCode, null, latency, LocalDateTime.now(),
                    rewriteLatency, retrieveLatency, rerankLatency, packLatency,
                    retrievalPath, candidateIds, selectedIds,
                    candidateIds.size(), selectedIds.size()));
        }
    }

    // ── Layer 2: 增强路径 ────────────────────────────────────────────

    /**
     * Per-stage timing and ID data returned from Layer 2 operations.
     */
    private record Layer2Timing(long rewriteLatencyMs, long retrieveLatencyMs, long rerankLatencyMs,
                                String retrievalPath,
                                List<Long> candidateIds, List<Long> selectedIds) {}

    private Layer2Timing layer2Enhance(RagContext ctx, ChatRequest request, String originalQuery) {
        // Phase 1: Query Rewrite
        long rewriteStart = System.currentTimeMillis();
        String conversationHistory = buildConversationHistory(request.chatId());
        RagRewriteResult rewrite = rewriteService.rewrite(originalQuery, conversationHistory);
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
        List<RagCandidate> candidates = hybridRetriever.retrieve(criteria);
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

        // Phase 3: Rerank
        long rerankStart = System.currentTimeMillis();
        List<RagCandidate> selected = reranker.rerank(candidates, criteria);
        long rerankLatency = System.currentTimeMillis() - rerankStart;
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
                retrievalPath, candidateIds, selectedIds);
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
}
