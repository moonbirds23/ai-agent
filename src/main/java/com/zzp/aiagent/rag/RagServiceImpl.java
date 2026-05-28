package com.zzp.aiagent.rag;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.rag.enhance.HybridGalleryRetriever;
import com.zzp.aiagent.rag.enhance.RagCandidate;
import com.zzp.aiagent.rag.enhance.RagContextPacker;
import com.zzp.aiagent.rag.enhance.RagQueryRewriteService;
import com.zzp.aiagent.rag.enhance.RagReranker;
import com.zzp.aiagent.rag.enhance.RagRewriteResult;
import com.zzp.aiagent.rag.enhance.RagSearchCriteria;
import com.zzp.aiagent.rag.enhance.RagTrace;
import com.zzp.aiagent.rag.enhance.RagTraceService;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.template.StyleTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("!test")
@Slf4j
public class RagServiceImpl implements RagService {

    private final ExplicitReferenceResolver explicitResolver;
    private final GalleryRagRetriever ragRetriever;
    private final StyleTemplateService templateService;
    private final RagProperties ragProperties;

    // Enhance components — only available when postgres profile is active
    private final RagQueryRewriteService rewriteService;
    private final HybridGalleryRetriever hybridRetriever;
    private final RagReranker reranker;
    private final RagContextPacker packer;
    private final RagTraceService traceService;

    private final boolean enhanceAvailable;

    public RagServiceImpl(ExplicitReferenceResolver explicitResolver,
                          GalleryRagRetriever ragRetriever,
                          StyleTemplateService templateService,
                          RagProperties ragProperties,
                          ObjectProvider<RagQueryRewriteService> rewriteProvider,
                          ObjectProvider<HybridGalleryRetriever> hybridProvider,
                          ObjectProvider<RagReranker> rerankerProvider,
                          ObjectProvider<RagContextPacker> packerProvider,
                          ObjectProvider<RagTraceService> traceProvider) {
        this.explicitResolver = explicitResolver;
        this.ragRetriever = ragRetriever;
        this.templateService = templateService;
        this.ragProperties = ragProperties;
        this.rewriteService = rewriteProvider.getIfAvailable();
        this.hybridRetriever = hybridProvider.getIfAvailable();
        this.reranker = rerankerProvider.getIfAvailable();
        this.packer = packerProvider.getIfAvailable();
        this.traceService = traceProvider.getIfAvailable();
        this.enhanceAvailable = rewriteService != null && hybridRetriever != null
                && reranker != null && packer != null;
    }

    @Override
    public RagContext buildContext(ChatRequest request) {
        long start = System.currentTimeMillis();
        RagContext ctx = RagContext.empty();
        String originalQuery = request.message();

        // Layer 1: 明确参考图（最高优先级）—— 两个路径共用
        if (request.referencePictureIds() != null && !request.referencePictureIds().isEmpty()) {
            List<RagContext.ReferencePicture> refs = explicitResolver.resolve(request.referencePictureIds());
            for (RagContext.ReferencePicture ref : refs) {
                ctx.addExplicit(ref);
            }
        }

        // Layer 2: RAG 检索
        if (request.useGalleryRag() == null || request.useGalleryRag()) {
            if (enhanceAvailable && originalQuery != null && !originalQuery.isBlank()) {
                layer2Enhance(ctx, request, originalQuery);
            } else {
                layer2Legacy(ctx, originalQuery);
            }
        }

        // Layer 3: 风格模板降级兜底
        layer3Template(ctx, request);

        // Trace
        if (traceService != null) {
            long latency = System.currentTimeMillis() - start;
            String rewritten = (String) ctx.getTrace().get("rewrittenQuery");
            String templateCode = ctx.getStyleTemplate() != null ? ctx.getStyleTemplate().code() : null;
            traceService.record(new RagTrace(
                    request.chatId(), null, originalQuery, rewritten,
                    ctx.getTrace().get("criteria"),
                    ctx.getTrace().get("candidates"),
                    ctx.getTrace().get("selected"),
                    templateCode, null, latency, null));
        }

        log.info("[RagService] 上下文构建完成: explicit={}, retrieved={}, template={}, enhance={}",
                ctx.getExplicitReferences().size(),
                ctx.getRetrievedReferences().size(),
                ctx.getStyleTemplate() != null ? ctx.getStyleTemplate().code() : "none",
                enhanceAvailable);
        return ctx;
    }

    // ── Layer 2: 增强路径 ────────────────────────────────────────────

    private void layer2Enhance(RagContext ctx, ChatRequest request, String originalQuery) {
        RagRewriteResult rewrite = rewriteService.rewrite(originalQuery, "");
        ctx.putTrace("rewrittenQuery", rewrite.searchQuery());

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

        List<RagCandidate> candidates = hybridRetriever.retrieve(criteria);
        ctx.putTrace("candidates", candidates);

        List<RagCandidate> selected = reranker.rerank(candidates, criteria);
        ctx.putTrace("selected", selected);

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
    }

    // ── Layer 2: 传统路径 ────────────────────────────────────────────

    private void layer2Legacy(RagContext ctx, String query) {
        if (query == null || query.isBlank()) return;
        List<RagContext.ReferencePicture> retrieved = ragRetriever.retrieve(query);
        for (RagContext.ReferencePicture ref : retrieved) {
            ctx.addRetrieved(ref);
        }
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
