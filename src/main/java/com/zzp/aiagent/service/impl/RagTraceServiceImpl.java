package com.zzp.aiagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.domain.rag.RagTrace;
import com.zzp.aiagent.service.RagTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
@Slf4j
public class RagTraceServiceImpl implements RagTraceService {

    private final ObjectMapper objectMapper;

    public RagTraceServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(RagTrace trace) {
        try {
            log.info("[RAG-Trace] chatId={} originalQuery=\"{}\" rewritten=\"{}\" template={} "
                    + "latency={}ms (rewrite={}ms retrieve={}ms rerank={}ms pack={}ms) "
                    + "path={} candidates={} selected={} promptLen={}",
                    trace.chatId(),
                    truncate(trace.originalQuery(), 80),
                    trace.rewrittenQuery() != null ? truncate(trace.rewrittenQuery(), 80) : "N/A",
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

            if (log.isDebugEnabled()) {
                log.debug("[RAG-Trace] 详细: {}", objectMapper.writeValueAsString(trace));
            }
        } catch (Exception e) {
            log.warn("[RAG-Trace] 记录失败", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
