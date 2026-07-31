package com.zzp.aiagent.observability;

/**
 * Request-local identifier for public, synthetic trace demonstrations.
 * It is intentionally separate from the user's natural-language prompt.
 */
public final class DemoCaseContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static void bind(String caseId) {
        if (caseId != null && caseId.matches("[A-Za-z0-9_-]{1,80}")) {
            CURRENT.set(caseId);
        }
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private DemoCaseContext() {
    }
}
