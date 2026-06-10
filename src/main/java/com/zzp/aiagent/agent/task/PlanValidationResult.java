package com.zzp.aiagent.agent.task;

import java.util.List;

public record PlanValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings
) {
    public static PlanValidationResult passed() {
        return new PlanValidationResult(true, List.of(), List.of());
    }

    public static PlanValidationResult invalid(List<String> errors) {
        return new PlanValidationResult(false, errors, List.of());
    }

    public static PlanValidationResult invalid(List<String> errors, List<String> warnings) {
        return new PlanValidationResult(false, errors, warnings);
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    /** Alias for component accessor — use this to avoid confusion with the boolean component */
    public boolean isValid() {
        return valid;
    }
}
