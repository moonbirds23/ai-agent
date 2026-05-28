package com.zzp.aiagent.rag.enhance;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test")
public class RagRerankerImpl implements RagReranker {

    private static final double VECTOR_WEIGHT = 50.0;
    private static final double KEYWORD_WEIGHT = 15.0;
    private static final double METADATA_WEIGHT = 10.0;

    @Override
    public List<RagCandidate> rerank(List<RagCandidate> candidates, RagSearchCriteria criteria) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .map(c -> {
                    double finalScore = c.vectorScore() * VECTOR_WEIGHT
                            + c.keywordScore() * KEYWORD_WEIGHT
                            + c.metadataScore() * METADATA_WEIGHT;
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
