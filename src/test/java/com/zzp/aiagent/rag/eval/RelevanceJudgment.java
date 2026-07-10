package com.zzp.aiagent.rag.eval;

/**
 * A single human-annotated relevance judgment for a picture against a query.
 * grade: 0=irrelevant, 1=weakly relevant, 2=relevant, 3=highly relevant.
 */
public record RelevanceJudgment(
        long pictureId,
        String picHash,
        int grade
) {
    public RelevanceJudgment {
        if (grade < 0 || grade > 3) {
            throw new IllegalArgumentException("grade must be 0-3, got: " + grade);
        }
    }

    /** Convenience: is this judgment at least weakly relevant? */
    public boolean isRelevant() {
        return grade >= 1;
    }

    /** Convenience: is this judgment highly relevant? */
    public boolean isHighlyRelevant() {
        return grade >= 3;
    }
}
