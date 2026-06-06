package com.zzp.aiagent.agent.task;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Merges the model's natural-language response with the authoritative
 * verification result.
 * <p>
 * If the model falsely claims a task completed but {@link TaskVerifier}
 * found no evidence, the composer replaces the false claim with the
 * verifier's actual status message.
 */
@Slf4j
public final class ResponseComposer {

    private ResponseComposer() { /* utility */ }

    /**
     * Compose the final user-facing response.
     *
     * @param modelResponse     the raw response from the LLM
     * @param verificationResult result of task verification
     * @return a response safe to return to the user
     */
    public static String compose(String modelResponse, VerificationResult verificationResult) {
        if (modelResponse == null || modelResponse.isBlank()) {
            return verificationResult.userMessage();
        }

        return switch (verificationResult.status()) {
            case SUCCESS -> modelResponse; // model was truthful, pass through

            case FAILED -> {
                // Model claimed something that didn't happen — intercept
                log.warn("[ResponseComposer] 模型声称已完成但验收未通过，替换为实际状态");
                yield verificationResult.userMessage();
            }

            case PARTIAL_SUCCESS -> {
                // Augment the model response with the partial status
                String note = "\n\n【系统提示】" + verificationResult.userMessage();
                yield modelResponse + note;
            }

            case NEED_MORE_INFO -> verificationResult.userMessage();

            case REJECTED, TIMEOUT, MAX_STEPS_EXCEEDED -> verificationResult.userMessage();
        };
    }

    /**
     * Convenience: verify + compose in one step.
     */
    public static String composeVerified(String modelResponse, TaskLedger ledger, String turnId) {
        List<ToolExecutionRecord> records = ledger.getRecords(turnId);
        TaskType taskType = TaskVerifier.inferTaskType(records);
        VerificationResult vr = TaskVerifier.verify(taskType, records);
        return compose(modelResponse, vr);
    }
}
