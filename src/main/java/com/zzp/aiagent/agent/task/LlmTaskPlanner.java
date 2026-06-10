package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmTaskPlanner {

    private final TaskPlanner ruleBasedPlanner;
    private final TaskPlanValidator validator;
    private final TaskPlanRepair repair;

    public LlmTaskPlanner(TaskPlanner ruleBasedPlanner, TaskPlanValidator validator, TaskPlanRepair repair) {
        this.ruleBasedPlanner = ruleBasedPlanner;
        this.validator = validator;
        this.repair = repair;
    }

    public TaskPlan plan(ChatRequest request, String turnId) {
        TaskPlan plan = ruleBasedPlanner.plan(request, turnId);

        PlanValidationResult validation = validator.validate(plan);
        if (validation.valid()) {
            return plan;
        }

        log.info("[LlmTaskPlanner] Plan validation failed, attempting repair");
        TaskPlan repaired = repair.repair(plan, request.message());
        if (repaired != null) {
            return repaired;
        }

        log.warn("[LlmTaskPlanner] Repair failed, returning original plan with validation errors: {}",
                validation.errors());
        return plan;
    }
}
