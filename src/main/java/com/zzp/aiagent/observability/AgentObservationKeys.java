package com.zzp.aiagent.observability;

/**
 * Shared observation attributes. Constants are split by intended cardinality
 * so request identifiers never accidentally become metric tags.
 */
public final class AgentObservationKeys {

    public static final class Low {
        public static final String TASK_TYPE = "agent.task.type";
        public static final String EXECUTOR_TYPE = "agent.executor.type";
        public static final String PLAN_SOURCE = "agent.plan.source";
        public static final String OUTCOME = "agent.outcome";
        public static final String TOOL_NAME = "agent.tool.name";
        public static final String TOOL_MODE = "agent.tool.mode";
        public static final String RAG_PATH = "agent.rag.path";
        public static final String RAG_EMPTY = "agent.rag.empty";
        public static final String RAG_REFERENCE_MODE = "agent.rag.reference_mode";
        public static final String VERIFICATION_STATUS = "agent.verification.status";
        public static final String RECOVERY_TYPE = "agent.recovery.type";
        public static final String TOOL_SIDE_EFFECT = "agent.tool.side_effect";
        public static final String TOOL_RECOVERABLE = "agent.tool.recoverable";
        public static final String MCP_SERVER_NAME = "mcp.server.name";
        public static final String MCP_TRANSPORT = "mcp.transport";
        public static final String COST_STATUS = "agent.cost.status";

        private Low() {
        }
    }

    public static final class High {
        public static final String CHAT_ID = "agent.chat.id";
        public static final String TURN_ID = "agent.turn.id";
        public static final String TOOL_CALL_ID = "gen_ai.tool.call.id";
        public static final String STEP_CODE = "agent.step.code";
        public static final String ERROR_CODE = "error.code";
        public static final String RAG_CANDIDATE_COUNT = "agent.rag.candidate_count";
        public static final String RAG_SELECTED_COUNT = "agent.rag.selected_count";
        public static final String TOOL_ATTEMPT = "agent.tool.attempt";
        public static final String MODEL_INPUT_TOKENS = "gen_ai.usage.input_tokens";
        public static final String MODEL_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
        public static final String MODEL_TOTAL_TOKENS = "gen_ai.usage.total_tokens";
        public static final String ESTIMATED_COST = "agent.cost.estimated";
        public static final String COST_CURRENCY = "agent.cost.currency";
        public static final String PRICING_VERSION = "agent.cost.pricing_version";

        private High() {
        }
    }

    private AgentObservationKeys() {
    }
}
