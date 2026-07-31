package com.zzp.aiagent.agent.task;

/**
 * Describes how the effective task plan was produced.
 */
public enum PlanSource {
    RULE,
    LLM,
    REPAIRED,
    RULE_FALLBACK
}
