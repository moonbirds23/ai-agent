package com.zzp.aiagent.observability;

import java.math.BigDecimal;

public record CostEstimate(
        Status status,
        BigDecimal amount,
        String currency,
        String pricingVersion
) {
    public enum Status {
        CALCULATED,
        DISABLED,
        USAGE_MISSING,
        PRICE_MISSING
    }

    public static CostEstimate unavailable(Status status, String currency) {
        return new CostEstimate(status, null, currency, null);
    }
}
