package com.zzp.aiagent.tool;

import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;

public interface ToolExecutor {

    ToolExecutionRecord execute(String turnId, TaskStep step, ToolExecutionContext context);
}
