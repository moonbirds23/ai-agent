package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskGovernanceTest {

    private final TaskPlanner planner = new TaskPlanner();
    private final TaskPlanValidator validator =
            new TaskPlanValidator(new ToolCapabilityRegistry());

    @Test
    void galleryThenGenerationCreatesOrderedPlan() {
        ChatRequest request = request(
                "先在图库查找几张关于雪景的图片，再根据这些图片生成雪景海报");

        TaskPlan plan = planner.plan(request, "turn-1");

        assertThat(plan.taskType()).isEqualTo(TaskType.CREATIVE_WORKFLOW);
        assertThat(plan.steps()).extracting(TaskStep::toolName)
                .containsSubsequence("searchGallery", "generateImage");
        TaskStep generation = findTool(plan, "generateImage");
        assertThat(generation.dependsOn()).contains("search_gallery");
        assertThat(validator.validate(plan, request).valid()).isTrue();
    }

    @Test
    void galleryReferenceSearchDoesNotTriggerGenerationByNounAlone() {
        TaskPlan plan = planner.plan(request(
                "在我的图库里找几张适合做冬日海报的参考图"), "turn-gallery-only");

        assertThat(plan.taskType()).isEqualTo(TaskType.GALLERY_SEARCH);
        assertThat(plan.steps()).extracting(TaskStep::toolName)
                .containsExactly("searchGallery");
    }

    @Test
    void validatorRejectsPlanThatOmitsRequestedGallerySearch() {
        ChatRequest request = request("先从图库搜索雪景，再生成海报");
        TaskPlan incomplete = new TaskPlan(
                "turn-2", TaskType.IMAGE_GENERATION, request.message(),
                List.of(TaskStep.of("generate_image", "生成图片", true, "generateImage")),
                false, true, false, Map.of());

        PlanValidationResult result = validator.validate(incomplete, request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("searchGallery"));
    }

    @Test
    void requiredStepsAreCheckedBeforePartialSuccess() {
        TaskPlan plan = planner.plan(request(
                "先在图库找雪景再生成海报"), "turn-3");
        ToolExecutionRecord generated = ToolExecutionRecord.success(
                "turn-3", "generateImage", Map.of(),
                Map.of("imageUrl", "https://example.test/image.png"),
                ToolExecutionRecord.IMAGE_GENERATED);

        VerificationResult result = TaskVerifier.verify(plan, List.of(generated));

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.userMessage()).contains("搜索本地图库");
    }

    @Test
    void pureGenerationRejectsUnrequestedPexelsSearch() {
        ChatRequest request = request(
                "现在请真正调用工具，生成一张极简风格的智能耳机产品宣传图，白色背景，柔和光影");
        TaskPlan invalid = planWithPexelsDependency(request, "turn-pure-generation");

        PlanValidationResult result = validator.validate(invalid, request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error ->
                error.contains("must not add an unrequested search"));
    }

    @Test
    void repairRemovesSearchFromPureGenerationPlan() {
        ChatRequest request = request(
                "生成一张极简风格的智能耳机产品宣传图，白色背景，柔和光影");
        TaskPlan invalid = planWithPexelsDependency(request, "turn-repair-pure");

        TaskPlan repaired = new TaskPlanRepair(validator).repair(invalid, request);

        assertThat(repaired).isNotNull();
        assertThat(repaired.steps()).extracting(TaskStep::toolName)
                .contains("generateImage")
                .doesNotContain("pexelsSearchPhotos");
        assertThat(findTool(repaired, "generateImage").dependsOn()).isEmpty();
        assertThat(validator.validate(repaired, request).valid()).isTrue();
    }

    @Test
    void repairUsesActualSearchStepCodeForDependency() {
        ChatRequest request = request("先在图库搜索雪景图片，再生成一张冬日海报");
        TaskPlan invalid = new TaskPlan(
                "turn-dynamic-code", TaskType.IMAGE_GENERATION, request.message(),
                List.of(
                        TaskStep.of("step_abc", "Search gallery", true, "searchGallery"),
                        TaskStep.of("step_xyz", "Generate image", true, "generateImage")),
                false, true, false, Map.of());

        TaskPlan repaired = new TaskPlanRepair(validator).repair(invalid, request);

        assertThat(repaired).isNotNull();
        assertThat(findTool(repaired, "generateImage").dependsOn())
                .containsExactly("step_abc");
        assertThat(validator.validate(repaired, request).valid()).isTrue();
    }

    @Test
    void manualCapabilityIncludesPexelsSearch() {
        ToolCapabilityRegistry capabilities = new ToolCapabilityRegistry();

        assertThat(capabilities.supportsManual("pexelsSearchPhotos")).isTrue();
        assertThat(capabilities.supportsManual("generateImage")).isTrue();
        assertThat(capabilities.supportsManual("webSearch")).isFalse();
    }

    @Test
    void conflictingNoToolGenerationRequiresClarification() {
        TaskPlan plan = planner.plan(request(
                "不要调用任何工具，生成一张极简耳机产品图"), "turn-conflict");

        assertThat(plan.taskType()).isEqualTo(TaskType.NEED_CLARIFICATION);
        assertThat(plan.steps()).extracting(TaskStep::toolName)
                .containsOnlyNulls();
        assertThat(plan.userGoal()).contains("必须调用工具");
    }

    @Test
    void repairMakesExplicitPexelsWorkflowRequired() {
        ChatRequest request = request(
                "先在 Pexels 搜索耳机参考图，再根据搜索结果生成产品宣传图");
        TaskPlan invalid = new TaskPlan(
                "turn-pexels-workflow", TaskType.IMAGE_GENERATION, request.message(),
                List.of(
                        TaskStep.of("search_step", "Search Pexels", false,
                                "pexelsSearchPhotos"),
                        new TaskStep("generate_step", "Generate image", false,
                                "generateImage", List.of("search_step"), Map.of(),
                                StepStatus.PENDING)),
                false, true, true, Map.of());

        TaskPlan repaired = new TaskPlanRepair(validator).repair(invalid, request);

        assertThat(repaired).isNotNull();
        assertThat(repaired.taskType()).isEqualTo(TaskType.CREATIVE_WORKFLOW);
        assertThat(repaired.steps())
                .filteredOn(step -> step.toolName() != null)
                .allMatch(TaskStep::required);
        assertThat(findTool(repaired, "generateImage").dependsOn())
                .containsExactly("search_step");
        assertThat(validator.validate(repaired, request).valid()).isTrue();
    }

    @Test
    void ledgerPreservesToolCallStartAndFinishTime() throws InterruptedException {
        TaskLedger ledger = new TaskLedger();
        ledger.beforeCall("turn-timing", "searchGallery", Map.of("query", "snow"));
        Thread.sleep(15);

        ledger.recordSuccess("turn-timing", "searchGallery", Map.of("query", "snow"),
                Map.of("resultCount", 1), ToolExecutionRecord.NONE);

        assertThat(ledger.getRecords("turn-timing")).singleElement()
                .satisfies(record -> {
                    assertThat(record.finishedAt()).isGreaterThan(record.startedAt());
                    assertThat(record.elapsedMs()).isGreaterThanOrEqualTo(10);
                });
    }

    @Test
    void ledgerFailurePreservesRecoveryHintAndTiming() throws InterruptedException {
        TaskLedger ledger = new TaskLedger();
        ledger.beforeCall("turn-failure", "generateImage", Map.of("prompt", "poster"));
        Thread.sleep(15);

        ledger.recordFailure("turn-failure", "generateImage",
                Map.of("prompt", "poster"), "provider unavailable");

        assertThat(ledger.getRecords("turn-failure")).singleElement()
                .satisfies(record -> {
                    assertThat(record.success()).isFalse();
                    assertThat(record.recoverable()).isTrue();
                    assertThat(record.recoveryHint()).isNotBlank();
                    assertThat(record.finishedAt()).isGreaterThan(record.startedAt());
                    assertThat(record.elapsedMs()).isGreaterThanOrEqualTo(10);
                });
    }

    @Test
    void noSaveConstraintRejectsOtherwiseSuccessfulTurnWithGalleryWrite() {
        VerificationResult initiallySuccessful =
                VerificationResult.success("已找到候选", Map.of());
        ToolExecutionRecord forbiddenWrite = ToolExecutionRecord.success(
                "turn-no-save", "pexelsSearchAndImport", Map.of(),
                Map.of("pictureId", 42L), ToolExecutionRecord.GALLERY_CREATED);

        VerificationResult result = TaskVerifier.enforceNoSaveConstraint(
                initiallySuccessful, true, List.of(forbiddenWrite));

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.deliverable()).isFalse();
        assertThat(result.userMessage()).contains("检测到图库写入");
    }

    @Test
    void validatorRejectsGalleryWriteStepForNoSaveRequest() {
        ChatRequest request = request("在 Pexels 找 3 张图片，只返回候选，不保存");
        TaskPlan invalid = new TaskPlan(
                "turn-no-save-plan", TaskType.WEB_IMAGE_SEARCH, request.message(),
                List.of(
                        TaskStep.of("search", "Search Pexels", true,
                                "pexelsSearchPhotos"),
                        TaskStep.of("import", "Import candidate", true,
                                "pexelsSearchAndImport")),
                false, false, true, Map.of());

        PlanValidationResult result = validator.validate(invalid, request);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error ->
                error.contains("No-save request must not contain gallery write step"));
    }

    @Test
    void repairDeduplicatesWebImageSearchToolForStableAutoExecution() {
        ChatRequest request = request(
                "在 Pexels 找 3 张城市夜景图片，只返回候选，不保存");
        TaskPlan duplicate = new TaskPlan(
                "turn-duplicate-pexels", TaskType.WEB_IMAGE_SEARCH,
                request.message(),
                List.of(
                        TaskStep.of("search-1", "Search Pexels", true,
                                "pexelsSearchPhotos"),
                        TaskStep.of("search-2", "Search Pexels again", true,
                                "pexelsSearchPhotos")),
                false, false, true, Map.of());

        assertThat(validator.validate(duplicate, request).valid()).isFalse();

        TaskPlan repaired = new TaskPlanRepair(validator).repair(duplicate, request);

        assertThat(repaired).isNotNull();
        assertThat(repaired.steps()).extracting(TaskStep::toolName)
                .containsExactly("pexelsSearchPhotos");
        assertThat(validator.validate(repaired, request).valid()).isTrue();
    }

    private static TaskPlan planWithPexelsDependency(ChatRequest request, String turnId) {
        return new TaskPlan(
                turnId, TaskType.IMAGE_GENERATION, request.message(),
                List.of(
                        TaskStep.of("step1", "Search reference photos", true,
                                "pexelsSearchPhotos"),
                        new TaskStep("step2", "Generate image", true, "generateImage",
                                List.of("step1"), Map.of(), StepStatus.PENDING)),
                false, true, true, Map.of());
    }

    private static TaskStep findTool(TaskPlan plan, String toolName) {
        return plan.steps().stream()
                .filter(step -> toolName.equals(step.toolName()))
                .findFirst()
                .orElseThrow();
    }

    private static ChatRequest request(String message) {
        return new ChatRequest(message, null, null, null, null);
    }
}
