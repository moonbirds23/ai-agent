package com.zzp.aiagent.observability;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class AgentCostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final AgentObservabilityProperties observabilityProperties;
    private final AgentPricingProperties pricingProperties;

    public AgentCostCalculator(AgentObservabilityProperties observabilityProperties,
                               AgentPricingProperties pricingProperties) {
        this.observabilityProperties = observabilityProperties;
        this.pricingProperties = pricingProperties;
    }

    public CostEstimate estimate(String model, ModelUsage usage) {
        if (!observabilityProperties.pricingEnabled()) {
            return CostEstimate.unavailable(CostEstimate.Status.DISABLED, pricingProperties.currency());
        }
        if (usage == null || !usage.available()) {
            return CostEstimate.unavailable(CostEstimate.Status.USAGE_MISSING, pricingProperties.currency());
        }
        AgentPricingProperties.ModelPrice price = model == null
                ? null
                : pricingProperties.models().get(model);
        if (price == null) {
            return CostEstimate.unavailable(CostEstimate.Status.PRICE_MISSING, pricingProperties.currency());
        }

        BigDecimal inputCost = BigDecimal.valueOf(usage.inputTokens())
                .multiply(price.inputPerMillion())
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(usage.outputTokens())
                .multiply(price.outputPerMillion())
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        return new CostEstimate(
                CostEstimate.Status.CALCULATED,
                inputCost.add(outputCost).stripTrailingZeros(),
                pricingProperties.currency(),
                price.version()
        );
    }
}
