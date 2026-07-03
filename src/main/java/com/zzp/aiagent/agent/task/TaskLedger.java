package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@Profile("!test")
public class TaskLedger {

    // Per-turn limits
    static final int MAX_TOOL_CALLS_PER_TURN = 8;
    static final int MAX_GENERATION_CALLS_PER_TURN = 1;
    static final int MAX_DOWNLOAD_CALLS_PER_TURN = 5;
    static final int MAX_SEARCH_CALLS_PER_TURN = 3;

    private final ConcurrentMap<String, List<ToolExecutionRecord>> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> callCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskPlan> plans = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskLifecycleStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, VerificationResult> verifications = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, StepStatus>> stepStatuses = new ConcurrentHashMap<>();

    // ── lifecycle ───────────────────────────────────────────────────

    /** Called at the start of every @Tool method. Returns the current call count. */
    public int beforeCall(String turnId, String toolName, Map<String, Object> input) {
        if (turnId == null || turnId.isBlank()) return 0;
        statuses.put(turnId, TaskLifecycleStatus.RUNNING);
        int count = callCounts.merge(turnId, 1, Integer::sum);

        // Global limit
        if (count > MAX_TOOL_CALLS_PER_TURN) {
            throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                    "本轮工具调用次数超限（" + MAX_TOOL_CALLS_PER_TURN + "次）");
        }

        // Per-tool-type limits
        List<ToolExecutionRecord> existing = records.getOrDefault(turnId, List.of());
        if ("generateImage".equals(toolName)) {
            long genCount = existing.stream().filter(r -> "generateImage".equals(r.toolName())).count();
            if (genCount >= MAX_GENERATION_CALLS_PER_TURN) {
                throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                        "本轮生图次数超限（" + MAX_GENERATION_CALLS_PER_TURN + "次）");
            }
        }
        if (isSearchTool(toolName)) {
            long searchCount = existing.stream().filter(r -> isSearchTool(r.toolName())).count();
            if (searchCount >= MAX_SEARCH_CALLS_PER_TURN) {
                throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                        "本轮搜索次数超限（" + MAX_SEARCH_CALLS_PER_TURN + "次）");
            }
        }
        if (isDownloadTool(toolName)) {
            long dlCount = existing.stream().filter(r -> isDownloadTool(r.toolName())).count();
            if (dlCount >= MAX_DOWNLOAD_CALLS_PER_TURN) {
                throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                        "本轮下载次数超限（" + MAX_DOWNLOAD_CALLS_PER_TURN + "次）");
            }
        }

        return count;
    }

    /** Record a successful tool execution. */
    public void recordSuccess(String turnId, String toolName,
                              Map<String, Object> input, Map<String, Object> output,
                              String sideEffect) {
        if (turnId == null || turnId.isBlank()) return;
        ToolExecutionRecord record = ToolExecutionRecord.success(turnId, toolName, input, output, sideEffect);
        append(turnId, record);
        statuses.put(turnId, TaskLifecycleStatus.RUNNING);
        log.debug("[TaskLedger] {} success turnId={} sideEffect={}", toolName, turnId, sideEffect);
    }

    /** Record a failed tool execution. */
    public void recordFailure(String turnId, String toolName,
                              Map<String, Object> input, String errorMessage) {
        if (turnId == null || turnId.isBlank()) return;
        ToolExecutionRecord record = ToolExecutionRecord.failure(turnId, toolName, input, errorMessage);
        append(turnId, record);
        statuses.put(turnId, TaskLifecycleStatus.RUNNING);
        log.debug("[TaskLedger] {} failed turnId={} error={}", toolName, turnId, errorMessage);
    }

    // ── task plan lifecycle ────────────────────────────────────────

    public void startPlan(TaskPlan plan) {
        if (plan == null || plan.turnId() == null || plan.turnId().isBlank()) return;
        plans.put(plan.turnId(), plan);
        statuses.put(plan.turnId(), TaskLifecycleStatus.PLANNED);
        log.debug("[TaskLedger] plan started turnId={} type={}", plan.turnId(), plan.taskType());
    }

    public TaskPlan getPlan(String turnId) {
        return turnId != null ? plans.get(turnId) : null;
    }

    public void markVerifying(String turnId) {
        if (turnId != null && !turnId.isBlank()) {
            statuses.put(turnId, TaskLifecycleStatus.VERIFYING);
        }
    }

    public void completeVerification(String turnId, VerificationResult result) {
        if (turnId == null || turnId.isBlank() || result == null) return;
        verifications.put(turnId, result);
        statuses.put(turnId, toLifecycle(result.status()));
        TaskPlan plan = plans.get(turnId);
        if (plan != null) {
            Map<String, Boolean> verifiedSteps = TaskVerifier.verifySteps(plan, getRecords(turnId));
            Map<String, StepStatus> current = stepStatuses.computeIfAbsent(
                    turnId, ignored -> new ConcurrentHashMap<>());
            verifiedSteps.forEach((code, success) ->
                    current.put(code, success ? StepStatus.SUCCESS : StepStatus.FAILED));
        }
    }

    public VerificationResult getVerification(String turnId) {
        return turnId != null ? verifications.get(turnId) : null;
    }

    public TaskStatusSnapshot snapshot(String turnId) {
        return snapshot(turnId, null);
    }

    public TaskStatusSnapshot snapshot(String turnId, RecoveryPolicy recoveryPolicy) {
        TaskPlan plan = getPlan(turnId);
        List<ToolExecutionRecord> evidence = getRecords(turnId);
        VerificationResult verification = getVerification(turnId);
        TaskLifecycleStatus status = statuses.getOrDefault(turnId, TaskLifecycleStatus.PLANNED);
        RecoveryAction recovery = recoveryPolicy != null && verification != null
                ? recoveryPolicy.decide(plan, verification, evidence)
                : null;
        return new TaskStatusSnapshot(
                turnId,
                plan != null ? plan.taskType() : TaskVerifier.inferTaskType(evidence),
                status,
                plan != null ? plan.userGoal() : "",
                plan != null ? plan.steps() : List.of(),
                getStepStatuses(turnId),
                verification,
                recovery,
                evidence);
    }

    // ── step-level tracking ─────────────────────────────────────────

    public void startStep(String turnId, String stepCode) {
        if (turnId == null || turnId.isBlank() || stepCode == null) return;
        stepStatuses.computeIfAbsent(turnId, k -> new ConcurrentHashMap<>())
                .put(stepCode, StepStatus.RUNNING);
        statuses.put(turnId, TaskLifecycleStatus.RUNNING);
        log.debug("[TaskLedger] step started turnId={} step={}", turnId, stepCode);
    }

    public void completeStep(String turnId, String stepCode, ToolExecutionRecord record) {
        if (turnId == null || turnId.isBlank() || stepCode == null) return;
        stepStatuses.computeIfAbsent(turnId, k -> new ConcurrentHashMap<>())
                .put(stepCode, StepStatus.SUCCESS);
        if (record != null) {
            append(turnId, record);
        }
        log.debug("[TaskLedger] step completed turnId={} step={}", turnId, stepCode);
    }

    public void failStep(String turnId, String stepCode, String error) {
        if (turnId == null || turnId.isBlank() || stepCode == null) return;
        stepStatuses.computeIfAbsent(turnId, k -> new ConcurrentHashMap<>())
                .put(stepCode, StepStatus.FAILED);
        log.debug("[TaskLedger] step failed turnId={} step={} error={}", turnId, stepCode, error);
    }

    public void skipStep(String turnId, String stepCode) {
        if (turnId == null || turnId.isBlank() || stepCode == null) return;
        stepStatuses.computeIfAbsent(turnId, k -> new ConcurrentHashMap<>())
                .put(stepCode, StepStatus.SKIPPED);
    }

    public StepStatus getStepStatus(String turnId, String stepCode) {
        if (turnId == null || stepCode == null) return StepStatus.PENDING;
        Map<String, StepStatus> map = stepStatuses.get(turnId);
        return map != null ? map.getOrDefault(stepCode, StepStatus.PENDING) : StepStatus.PENDING;
    }

    public Map<String, StepStatus> getStepStatuses(String turnId) {
        if (turnId == null) return Map.of();
        Map<String, StepStatus> map = stepStatuses.get(turnId);
        return map != null ? Map.copyOf(map) : Map.of();
    }

    // ── query ───────────────────────────────────────────────────────

    /** All records for a turn, newest first. */
    public List<ToolExecutionRecord> getRecords(String turnId) {
        if (turnId == null) return List.of();
        List<ToolExecutionRecord> list = records.get(turnId);
        if (list == null) return List.of();
        List<ToolExecutionRecord> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    /** Find the most recent successful record for a given tool name. */
    public ToolExecutionRecord findLastSuccess(String turnId, String toolName) {
        List<ToolExecutionRecord> list = records.get(turnId);
        if (list == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            ToolExecutionRecord r = list.get(i);
            if (r.success() && r.toolName().equals(toolName)) return r;
        }
        return null;
    }

    /** Count successful calls of a given tool in this turn. */
    public long countSuccess(String turnId, String toolName) {
        List<ToolExecutionRecord> list = records.get(turnId);
        if (list == null) return 0;
        return list.stream().filter(r -> r.success() && r.toolName().equals(toolName)).count();
    }

    /** Total call count for this turn. */
    public int callCount(String turnId) {
        return callCounts.getOrDefault(turnId, 0);
    }

    /** Clear all records for a turn. */
    public void clear(String turnId) {
        if (turnId != null && !turnId.isBlank()) {
            records.remove(turnId);
            callCounts.remove(turnId);
            plans.remove(turnId);
            statuses.remove(turnId);
            verifications.remove(turnId);
            stepStatuses.remove(turnId);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    private void append(String turnId, ToolExecutionRecord record) {
        records.computeIfAbsent(turnId, k -> new ArrayList<>()).add(record);
    }

    private static boolean isSearchTool(String name) {
        return "imageSearch".equals(name) || "pexelsSearchPhotos".equals(name)
                || "webSearch".equals(name) || "searchGallery".equals(name);
    }

    private static boolean isDownloadTool(String name) {
        return "downloadImage".equals(name) || "searchAndDownload".equals(name)
                || "pexelsSearchAndImport".equals(name) || "importImage".equals(name);
    }

    private static TaskLifecycleStatus toLifecycle(TaskStatus status) {
        return switch (status) {
            case SUCCESS -> TaskLifecycleStatus.SUCCESS;
            case PARTIAL_SUCCESS -> TaskLifecycleStatus.PARTIAL_SUCCESS;
            case NEED_MORE_INFO -> TaskLifecycleStatus.NEED_MORE_INFO;
            default -> TaskLifecycleStatus.FAILED;
        };
    }
}
