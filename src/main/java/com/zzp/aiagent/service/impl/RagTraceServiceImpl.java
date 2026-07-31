package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.domain.rag.RagTrace;
import com.zzp.aiagent.observability.TelemetrySanitizer;
import com.zzp.aiagent.service.RagTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@Slf4j
public class RagTraceServiceImpl implements RagTraceService {

    private final TelemetrySanitizer sanitizer;

    public RagTraceServiceImpl(TelemetrySanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @Override
    public void record(RagTrace trace) {
        try {
            log.info("[RAG-Trace] chatId={} originalQuery={} rewritten={} template={} "
                    + "latency={}ms (rewrite={}ms retrieve={}ms rerank={}ms pack={}ms) "
                    + "path={} candidates={} selected={} promptLen={}",
                    trace.chatId(),
                    sanitizer.summarizeText(trace.originalQuery()),
                    sanitizer.summarizeText(trace.rewrittenQuery()),
                    trace.templateCode() != null ? trace.templateCode() : "none",
                    trace.latencyMs(),
                    trace.rewriteLatencyMs(),
                    trace.retrieveLatencyMs(),
                    trace.rerankLatencyMs(),
                    trace.packLatencyMs(),
                    trace.retrievalPath(),
                    trace.candidateCount(),
                    trace.selectedCount(),
                    trace.enhancedPrompt() != null ? trace.enhancedPrompt().length() : 0);

        } catch (Exception e) {
            log.warn("[RAG-Trace] 记录失败", e);
        }
    }
}
