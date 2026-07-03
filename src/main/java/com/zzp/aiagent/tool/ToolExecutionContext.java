package com.zzp.aiagent.tool;

import com.zzp.aiagent.agent.executor.AgentInput;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolExecutionContext {

    private final AgentInput input;
    private final Map<String, ToolExecutionRecord> completedSteps = new LinkedHashMap<>();

    public ToolExecutionContext(AgentInput input) {
        this.input = input;
    }

    public AgentInput input() {
        return input;
    }

    public void record(String stepCode, ToolExecutionRecord record) {
        completedSteps.put(stepCode, record);
    }

    public List<ToolExecutionRecord> completedRecords() {
        return List.copyOf(completedSteps.values());
    }
}
