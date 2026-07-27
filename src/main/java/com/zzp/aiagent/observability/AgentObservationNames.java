package com.zzp.aiagent.observability;

/**
 * Stable observation names for the Agent execution lifecycle.
 */
public final class AgentObservationNames {

    public static final String TURN = "agent.turn";
    public static final String PLANNER = "agent.planner";
    public static final String EXECUTOR = "agent.executor";
    public static final String STEP = "agent.step";
    public static final String TOOL = "agent.tool";
    public static final String MCP_CALL = "agent.mcp.call";
    public static final String RAG = "agent.rag";
    public static final String RAG_REWRITE = "agent.rag.rewrite";
    public static final String RAG_RETRIEVE = "agent.rag.retrieve";
    public static final String RAG_RERANK = "agent.rag.rerank";
    public static final String RAG_PACK = "agent.rag.pack";
    public static final String VERIFIER = "agent.verifier";
    public static final String RECOVERY = "agent.recovery";
    public static final String MEMORY_WRITE = "agent.memory.write";

    private AgentObservationNames() {
    }
}
