package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.task.StepStatus;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@Component
@Profile("!test")
public class ManualReactExecutor implements AgentExecutor {

    private final ChatClient chatClient;
    private final TaskLedger taskLedger;

    public ManualReactExecutor(ChatModel chatModel, ChatMemory chatMemory,
                                TaskLedger taskLedger) {
        this.taskLedger = taskLedger;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    @Override
    public AgentResult execute(AgentInput input) {
        TaskPlan plan = input.plan();
        if (plan == null) {
            return new AgentResult(AgentState.ERROR, "ManualReactExecutor requires a TaskPlan", null);
        }

        String turnId = turnIdFrom(input.toolContext());
        log.info("[ManualReact] execute turnId={} type={} steps={}", turnId, plan.taskType(), plan.steps().size());

        StringBuilder output = new StringBuilder();
        for (TaskStep step : plan.steps()) {
            if (!dependenciesSatisfied(step, turnId)) {
                log.warn("[ManualReact] skipping step {}, dependencies not met", step.code());
                taskLedger.failStep(turnId, step.code(), "Dependencies not satisfied");
                if (step.required()) {
                    return new AgentResult(AgentState.ERROR,
                            "Required step '" + step.code() + "' blocked by failed dependency", null);
                }
                continue;
            }

            taskLedger.startStep(turnId, step.code());
            log.info("[ManualReact] executing step {}", step.code());

            try {
                String stepResult = executeStep(step, input);
                output.append(stepResult).append("\n");
                taskLedger.completeStep(turnId, step.code(), toolRecordForStep(turnId, step, stepResult));
            } catch (Exception e) {
                log.error("[ManualReact] step {} failed: {}", step.code(), e.getMessage());
                taskLedger.failStep(turnId, step.code(), e.getMessage());
                if (step.required()) {
                    return new AgentResult(AgentState.ERROR,
                            "Step '" + step.code() + "' failed: " + e.getMessage(), null);
                }
            }
        }

        return new AgentResult(AgentState.FINISHED, output.toString().trim(), null);
    }

    @Override
    public Flux<ChatClientResponse> stream(AgentInput input) {
        AgentResult result = execute(input);
        if (result.state() == AgentState.ERROR) {
            return Flux.error(new RuntimeException(result.content()));
        }
        return Flux.empty();
    }

    private boolean dependenciesSatisfied(TaskStep step, String turnId) {
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) return true;
        for (String dep : step.dependsOn()) {
            StepStatus depStatus = taskLedger.getStepStatus(turnId, dep);
            if (depStatus != StepStatus.SUCCESS) return false;
        }
        return true;
    }

    private String executeStep(TaskStep step, AgentInput input) {
        if (step.toolName() == null) {
            return chatClient.prompt()
                    .user(input.userText())
                    .call()
                    .content();
        }
        return chatClient.prompt()
                .user(step.description() + ": " + input.userText())
                .call()
                .content();
    }

    private ToolExecutionRecord toolRecordForStep(String turnId, TaskStep step, String result) {
        return ToolExecutionRecord.success(turnId,
                step.toolName() != null ? step.toolName() : "respond",
                step.input() != null ? step.input() : Map.of(),
                Map.of("result", result),
                ToolExecutionRecord.NONE);
    }

    private static String turnIdFrom(Map<String, Object> toolContext) {
        if (toolContext == null) return null;
        Object value = toolContext.get("turnId");
        return value instanceof String s ? s : null;
    }
}
