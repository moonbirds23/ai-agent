package com.zzp.aiagent.agent;

import com.zzp.aiagent.agent.executor.AgentExecutor;
import com.zzp.aiagent.agent.executor.AgentExecutorRouter;
import com.zzp.aiagent.agent.executor.AgentInput;
import com.zzp.aiagent.agent.executor.ManualReactExecutor;
import com.zzp.aiagent.agent.task.LlmTaskPlanner;
import com.zzp.aiagent.agent.task.RecoveryPolicy;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskStatusSnapshot;
import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.VerificationResult;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow engine that routes user intents to the appropriate execution strategy.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li><b>Intent classification</b> — delegates to {@link LlmTaskPlanner} to
 *       classify the user's request into a {@link TaskType} (image generation,
 *       gallery search, creative workflow, etc.) and produce a {@link TaskPlan}.</li>
 *   <li><b>Executor routing</b> — uses {@link AgentExecutorRouter} to select the
 *       best executor for the plan. Simple / chat turns go to
 *       {@code SpringAiAutoToolExecutor}; complex multi-step workflows with
 *       dependencies go to {@link ManualReactExecutor}.</li>
 *   <li><b>Execution coordination</b> — drives the selected {@link AgentExecutor}
 *       and tracks step-level progress via {@link TaskLedger}.</li>
 *   <li><b>Verification</b> — exposes the ledger and recovery policy so callers
 *       can verify delivery against the plan.</li>
 * </ul>
 * <p>
 * This class is the single entry point for "what should the system do for this
 * user turn, and how should it do it". The caller ({@code ChatServiceImpl})
 * handles pre-processing (image auto-save, media construction, memory) and
 * post-processing (response formatting, trusted memory writing).
 *
 * @see LlmTaskPlanner
 * @see AgentExecutorRouter
 * @see ManualReactExecutor
 */
@Slf4j
@Component
@Profile("!test")
public class WorkflowEngine {

    private final LlmTaskPlanner taskPlanner;
    private final AgentExecutorRouter executorRouter;
    private final TaskLedger taskLedger;
    private final RecoveryPolicy recoveryPolicy;

    public WorkflowEngine(LlmTaskPlanner taskPlanner,
                          AgentExecutorRouter executorRouter,
                          TaskLedger taskLedger,
                          RecoveryPolicy recoveryPolicy) {
        this.taskPlanner = taskPlanner;
        this.executorRouter = executorRouter;
        this.taskLedger = taskLedger;
        this.recoveryPolicy = recoveryPolicy;
    }

    /**
     * Plan a turn from a chat request.
     * <p>
     * Classifies the user's intent and produces a deterministic {@link TaskPlan}
     * that defines required steps, tool dependencies, and delivery expectations.
     * The plan is registered with the {@link TaskLedger} so downstream
     * verification can track progress against it.
     *
     * @param request the incoming chat request (message, mode, image, references)
     * @param chatId  the conversation identifier (used to generate a turn ID)
     * @return a fully-initialized plan registered with the ledger
     */
    public TaskPlan plan(ChatRequest request, String chatId) {
        String turnId = chatId + ":" + UUID.randomUUID();
        TaskPlan plan = taskPlanner.plan(request, turnId);
        taskLedger.startPlan(plan);
        log.info("[WorkflowEngine] plan turnId={} type={} steps={}",
                turnId, plan.taskType(), plan.steps().size());
        return plan;
    }

    /**
     * Select the executor best suited for the given plan.
     * <p>
     * For complex multi-step workflows ({@link TaskType#CREATIVE_WORKFLOW}) or
     * plans with explicit {@code dependsOn} chains, this returns a
     * {@link ManualReactExecutor} that executes steps in dependency order.
     * Simple chat or single-tool turns return the auto executor backed by
     * Spring AI Tool Calling.
     *
     * @param plan the task plan from {@link #plan(ChatRequest, String)}
     * @return the selected executor
     */
    public AgentExecutor selectExecutor(TaskPlan plan) {
        AgentExecutor executor = executorRouter.select(plan);
        log.info("[WorkflowEngine] selectExecutor type={} executor={}",
                plan.taskType(), executor.getClass().getSimpleName());
        return executor;
    }

    /**
     * Execute a plan synchronously with the best-fit executor.
     * <p>
     * This is the primary entry point for non-streaming turns. It combines
     * {@link #selectExecutor(TaskPlan)} and {@link AgentExecutor#execute(AgentInput)}
     * into a single call.
     *
     * @param input the pre-built agent input (user text, execution context, media, memory)
     * @param plan  the task plan
     * @return the agent execution result with state and output content
     */
    public AgentResult execute(AgentInput input, TaskPlan plan) {
        AgentExecutor executor = selectExecutor(plan);
        log.info("[WorkflowEngine] execute turnId={} type={} executor={}",
                plan.turnId(), plan.taskType(), executor.getClass().getSimpleName());
        return executor.execute(input);
    }

    /**
     * Execute a plan as a reactive stream.
     * <p>
     * For {@link ManualReactExecutor}, this wraps the synchronous
     * {@link AgentExecutor#execute(AgentInput)} into a {@link Flux}.
     * For auto executors, this delegates to the native streaming path.
     *
     * @param input the pre-built agent input
     * @param plan  the task plan
     * @return a Flux of {@link ChatClientResponse} for the caller to convert to SSE events
     */
    public Flux<ChatClientResponse> stream(AgentInput input, TaskPlan plan) {
        AgentExecutor executor = selectExecutor(plan);
        log.info("[WorkflowEngine] stream turnId={} type={} executor={}",
                plan.turnId(), plan.taskType(), executor.getClass().getSimpleName());
        return executor.stream(input);
    }

    /**
     * Whether the plan will be executed by a manual (step-by-step) executor.
     * Callers use this to decide whether to emit step-level SSE events.
     */
    public boolean isManualExecution(TaskPlan plan) {
        return executorRouter.isManual(plan);
    }

    /**
     * The ordered list of steps in the plan. Convenience accessor so callers
     * don't need to reach into the plan directly.
     */
    public List<TaskStep> steps(TaskPlan plan) {
        return plan.steps();
    }

    /**
     * The resolved task type for this plan.
     */
    public TaskType taskType(TaskPlan plan) {
        return plan.taskType();
    }

    /**
     * The turn ID generated during {@link #plan(ChatRequest, String)}.
     */
    public String turnId(TaskPlan plan) {
        return plan.turnId();
    }

    /**
     * The underlying ledger, exposed for verification and SSE event construction.
     */
    public TaskLedger ledger() {
        return taskLedger;
    }

    /**
     * The recovery policy, exposed for post-execution recovery suggestions.
     */
    public RecoveryPolicy recoveryPolicy() {
        return recoveryPolicy;
    }

    /**
     * Convenience: get a verification snapshot for a completed turn.
     */
    public VerificationResult verify(TaskPlan plan) {
        return taskLedger.getVerification(plan.turnId());
    }

    /**
     * Produce a structured snapshot of the current turn state (plan + step status
     * + recovery suggestions) for SSE task_verified / recovery_suggested events.
     */
    public TaskStatusSnapshot snapshot(TaskPlan plan) {
        return taskLedger.snapshot(plan.turnId(), recoveryPolicy);
    }

    /**
     * Clean up all ledger state for a turn. Callers must invoke this in a
     * {@code finally} block to prevent memory leaks.
     */
    public void cleanup(TaskPlan plan) {
        if (plan != null && plan.turnId() != null) {
            taskLedger.clear(plan.turnId());
        }
    }
}
