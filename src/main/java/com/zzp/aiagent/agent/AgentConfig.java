package com.zzp.aiagent.agent;

/**
 * Immutable Agent configuration.
 *
 * @param maxToolCalls hard limit on total tool invocations per turn
 * @param name         human-readable agent identifier
 */
public record AgentConfig(int maxToolCalls, String name) {

    public static final int DEFAULT_MAX_TOOL_CALLS = 8;

    public AgentConfig {
        if (maxToolCalls <= 0) {
            maxToolCalls = DEFAULT_MAX_TOOL_CALLS;
        }
    }

    public static AgentConfig of(String name) {
        return new AgentConfig(DEFAULT_MAX_TOOL_CALLS, name);
    }
}
