package com.zzp.aiagent.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCostCalculatorTest {

    @Test
    void calculatesVersionedCostFromProviderUsage() {
        AgentCostCalculator calculator = calculator(true, Map.of(
                "glm-test", new AgentPricingProperties.ModelPrice(
                        new BigDecimal("2.5"), new BigDecimal("5"), "2026-07")
        ));

        CostEstimate estimate = calculator.estimate("glm-test", new ModelUsage(2_000_000L, 500_000L));

        assertThat(estimate.status()).isEqualTo(CostEstimate.Status.CALCULATED);
        assertThat(estimate.amount()).isEqualByComparingTo("7.5");
        assertThat(estimate.currency()).isEqualTo("CNY");
        assertThat(estimate.pricingVersion()).isEqualTo("2026-07");
    }

    @Test
    void doesNotFabricateCostWhenUsageOrPriceIsMissing() {
        AgentCostCalculator calculator = calculator(true, Map.of());

        assertThat(calculator.estimate("unknown", new ModelUsage(1L, 2L)).status())
                .isEqualTo(CostEstimate.Status.PRICE_MISSING);
        assertThat(calculator.estimate("unknown", ModelUsage.unavailable()).status())
                .isEqualTo(CostEstimate.Status.USAGE_MISSING);
    }

    @Test
    void respectsPricingFeatureFlag() {
        AgentCostCalculator calculator = calculator(false, Map.of());

        assertThat(calculator.estimate("anything", new ModelUsage(1L, 2L)).status())
                .isEqualTo(CostEstimate.Status.DISABLED);
    }

    private AgentCostCalculator calculator(boolean pricingEnabled,
                                           Map<String, AgentPricingProperties.ModelPrice> models) {
        return new AgentCostCalculator(
                new AgentObservabilityProperties(true, pricingEnabled),
                new AgentPricingProperties("CNY", models)
        );
    }
}
