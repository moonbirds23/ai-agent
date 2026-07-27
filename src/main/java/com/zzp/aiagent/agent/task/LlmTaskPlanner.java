package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Profile("!test")
public class LlmTaskPlanner {

    private final TaskPlanner ruleBasedPlanner;
    private final TaskPlanValidator validator;
    private final TaskPlanRepair repair;
    private final ChatClient plannerClient;

    public LlmTaskPlanner(TaskPlanner ruleBasedPlanner, TaskPlanValidator validator,
                          TaskPlanRepair repair, ChatModel chatModel) {
        this.ruleBasedPlanner = ruleBasedPlanner;
        this.validator = validator;
        this.repair = repair;
        this.plannerClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        You are a backend task planner. Return a structured TaskPlanCandidate only.
                        Do not execute tools and do not answer the user.
                        Allowed tools: searchGallery, getPictureInfo, analyzeImage, generateImage,
                        pexelsSearchPhotos, pexelsSearchAndImport, webSearch, webFetch,
                        imageSearch, importImage, manageFavorite, listStyleTemplates.
                        If the user asks to search the gallery and then generate, include both steps
                        and make generateImage depend on the search step code.
                        If the user only asks to generate an image, plan generateImage only.
                        Never add gallery, Pexels, web, or image search merely to improve generation.
                        Add a search step only when the user explicitly asks to search or find references.
                        Use at most one generateImage step. Do not infer destructive actions.
                        """)
                .build();
    }

    public TaskPlan plan(ChatRequest request, String turnId) {
        return planWithMetadata(request, turnId).plan();
    }

    public PlanningResult planWithMetadata(ChatRequest request, String turnId) {
        TaskPlan fallback = ruleBasedPlanner.plan(request, turnId);
        if (fallback.taskType() == TaskType.CHAT
                || fallback.taskType() == TaskType.NEED_CLARIFICATION) {
            return new PlanningResult(fallback, PlanSource.RULE, false, false);
        }

        TaskPlan plan;
        try {
            TaskPlanCandidate candidate = plannerClient.prompt()
                    .user(buildPlannerInput(request))
                    .call()
                    .entity(TaskPlanCandidate.class);
            plan = normalize(candidate, fallback, turnId, request);
        } catch (Exception e) {
            log.warn("[LlmTaskPlanner] LLM planning failed, using rule fallback: {}", e.getMessage());
            return new PlanningResult(fallback, PlanSource.RULE_FALLBACK, false, false);
        }

        PlanValidationResult validation = validator.validate(plan, request);
        if (validation.valid()) {
            return new PlanningResult(plan,
                    plan == fallback ? PlanSource.RULE_FALLBACK : PlanSource.LLM,
                    false, false);
        }

        log.info("[LlmTaskPlanner] Plan validation failed, attempting repair");
        TaskPlan repaired = repair.repair(plan, request);
        if (repaired != null) {
            return new PlanningResult(repaired, PlanSource.REPAIRED, true, true);
        }

        log.warn("[LlmTaskPlanner] Repair failed, returning original plan with validation errors: {}",
                validation.errors());
        return new PlanningResult(fallback, PlanSource.RULE_FALLBACK, true, true);
    }

    private static String buildPlannerInput(ChatRequest request) {
        return """
                User request: %s
                Mode: %s
                Has uploaded image: %s
                Reference picture IDs: %s
                """.formatted(
                request.message() != null ? request.message() : "",
                request.mode(),
                (request.imageBase64() != null && !request.imageBase64().isBlank())
                        || (request.imageUrl() != null && !request.imageUrl().isBlank()),
                request.referencePictureIds() != null ? request.referencePictureIds() : List.of());
    }

    private static TaskPlan normalize(TaskPlanCandidate candidate, TaskPlan fallback,
                                      String turnId, ChatRequest request) {
        if (candidate == null || candidate.taskType() == null
                || candidate.steps() == null || candidate.steps().isEmpty()) {
            return fallback;
        }
        List<TaskStep> steps = candidate.steps().stream()
                .filter(step -> step != null && step.code() != null && !step.code().isBlank())
                .map(step -> new TaskStep(
                        step.code(),
                        step.description() != null ? step.description() : step.code(),
                        step.required(),
                        step.toolName(),
                        step.dependsOn() != null ? step.dependsOn() : List.of(),
                        step.input() != null ? step.input() : Map.of(),
                        StepStatus.PENDING))
                .toList();
        Map<String, Object> slots = new java.util.LinkedHashMap<>();
        if (candidate.slots() != null) slots.putAll(candidate.slots());
        if (request.referencePictureIds() != null && !request.referencePictureIds().isEmpty()) {
            slots.put("referencePictureIds", request.referencePictureIds());
        }
        return new TaskPlan(turnId, candidate.taskType(),
                candidate.userGoal() != null && !candidate.userGoal().isBlank()
                        ? candidate.userGoal() : fallback.userGoal(),
                steps,
                candidate.requiresImage(),
                candidate.requiresGeneration(),
                candidate.requiresExternalSearch(),
                Map.copyOf(slots));
    }

    public record TaskPlanCandidate(
            TaskType taskType,
            String userGoal,
            List<TaskStepCandidate> steps,
            boolean requiresImage,
            boolean requiresGeneration,
            boolean requiresExternalSearch,
            Map<String, Object> slots
    ) {}

    public record TaskStepCandidate(
            String code,
            String description,
            boolean required,
            String toolName,
            List<String> dependsOn,
            Map<String, Object> input
    ) {}
}
