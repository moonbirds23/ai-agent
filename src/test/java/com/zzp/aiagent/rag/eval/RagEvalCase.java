package com.zzp.aiagent.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;

import java.util.List;

/**
 * A single RAG evaluation test case loaded from cases.jsonl.
 * Each case defines a query, expected behavior, and human-annotated relevance judgments.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RagEvalCase(
        String caseId,
        boolean enabled,
        String group,
        String query,
        String conversationHistory,
        RagSearchCriteria fixedCriteria,
        ExpectedRewrite expectedRewrite,
        List<RelevanceJudgment> relevantPictures,
        List<Long> mustNotReturn,
        boolean expectedEmpty,
        String notes
) {
    public RagEvalCase(
            @JsonProperty("caseId") String caseId,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("group") String group,
            @JsonProperty("query") String query,
            @JsonProperty("conversationHistory") String conversationHistory,
            @JsonProperty("fixedCriteria") RagSearchCriteria fixedCriteria,
            @JsonProperty("expectedRewrite") ExpectedRewrite expectedRewrite,
            @JsonProperty("relevantPictures") List<RelevanceJudgment> relevantPictures,
            @JsonProperty("mustNotReturn") List<Long> mustNotReturn,
            @JsonProperty("expectedEmpty") boolean expectedEmpty,
            @JsonProperty("notes") String notes) {
        this.caseId = caseId;
        this.enabled = enabled;
        this.group = group;
        this.query = query;
        this.conversationHistory = conversationHistory;
        this.fixedCriteria = fixedCriteria;
        this.expectedRewrite = expectedRewrite;
        this.relevantPictures = relevantPictures != null ? List.copyOf(relevantPictures) : List.of();
        this.mustNotReturn = mustNotReturn != null ? List.copyOf(mustNotReturn) : List.of();
        this.expectedEmpty = expectedEmpty;
        this.notes = notes;
    }

    /**
     * Expected query rewrite outcomes — used in rewrite-only eval mode
     * to validate that the query rewriter produces the right search terms.
     */
    public record ExpectedRewrite(
            String searchQuery,
            List<String> tags,
            List<String> styleHints,
            List<String> colorHints,
            String category
    ) {
        public ExpectedRewrite(
                @JsonProperty("searchQuery") String searchQuery,
                @JsonProperty("tags") List<String> tags,
                @JsonProperty("styleHints") List<String> styleHints,
                @JsonProperty("colorHints") List<String> colorHints,
                @JsonProperty("category") String category) {
            this.searchQuery = searchQuery;
            this.tags = tags != null ? List.copyOf(tags) : List.of();
            this.styleHints = styleHints != null ? List.copyOf(styleHints) : List.of();
            this.colorHints = colorHints != null ? List.copyOf(colorHints) : List.of();
            this.category = category;
        }
    }
}
