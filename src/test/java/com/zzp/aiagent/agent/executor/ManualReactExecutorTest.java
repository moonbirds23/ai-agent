package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskStatus;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.TaskVerifier;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManualReactExecutorTest {

    @Test
    void failedBackendToolCannotBecomeSuccessEvidence() {
        TaskLedger ledger = new TaskLedger();
        ToolExecutor toolExecutor = (turnId, step, context) ->
                ToolExecutionRecord.failure(turnId, step.toolName(), step.input(), "backend failed");
        ManualReactExecutor executor = new ManualReactExecutor(ledger, toolExecutor);
        TaskPlan plan = new com.zzp.aiagent.agent.task.TaskPlanner().plan(
                new ChatRequest("先在图库找雪景再生成海报", null, null, null, null), "turn-1");
        ledger.startPlan(plan);

        AgentResult result = executor.execute(AgentInput.of(
                "先在图库找雪景再生成海报", "", List.of(), null,
                Map.of("turnId", "turn-1"), "chat-1", plan));

        assertThat(result.state()).isEqualTo(AgentState.ERROR);
        assertThat(ledger.countSuccess("turn-1", "searchGallery")).isZero();
        assertThat(TaskVerifier.verify(plan, ledger.getRecords("turn-1")).status())
                .isEqualTo(TaskStatus.FAILED);
    }
}
