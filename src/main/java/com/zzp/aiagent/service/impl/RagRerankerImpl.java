package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.domain.rag.RagProperties;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.service.RagReranker;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Profile("!test")
public class RagRerankerImpl implements RagReranker {

    private final RagProperties ragProperties;

    public RagRerankerImpl(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public List<RagCandidate> rerank(List<RagCandidate> candidates, RagSearchCriteria criteria) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // Min-max normalize each dimension across the batch to unify scale
        double maxVector = candidates.stream().mapToDouble(RagCandidate::vectorScore).max().orElse(1.0);
        double maxKeyword = candidates.stream().mapToDouble(RagCandidate::keywordScore).max().orElse(1.0);
        double maxMetadata = candidates.stream().mapToDouble(RagCandidate::metadataScore).max().orElse(1.0);

        return candidates.stream()
                .map(c -> {
                    double finalScore =
                            (c.vectorScore() / Math.max(maxVector, 1.0)) * ragProperties.vectorWeight()
                            + (c.keywordScore() / Math.max(maxKeyword, 1.0)) * ragProperties.keywordWeight()
                            + (c.metadataScore() / Math.max(maxMetadata, 1.0)) * ragProperties.metadataWeight();
                    List<String> reasons = buildReasons(c, finalScore);
                    return new RagCandidate(c.picture(), c.profile(),
                            c.vectorScore(), c.keywordScore(), c.metadataScore(),
                            finalScore, reasons);
                })
                .sorted((a, b) -> Double.compare(b.finalScore(), a.finalScore()))
                .limit(criteria.finalTopK())
                .toList();
    }

    private List<String> buildReasons(RagCandidate c, double finalScore) {
        List<String> reasons = new ArrayList<>();
        if (c.vectorScore() >= 0.6) reasons.add("语义高度匹配");
        else if (c.vectorScore() >= 0.4) reasons.add("语义相关");
        if (c.keywordScore() >= 20) reasons.add("标签高度匹配");
        else if (c.keywordScore() >= 10) reasons.add("标签部分匹配");
        if (c.metadataScore() >= 20) reasons.add("元数据高度匹配");
        else if (c.metadataScore() >= 10) reasons.add("元数据部分匹配");
        return reasons;
    }
}
