package com.zzp.aiagent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

@ConfigurationProperties("app.observability.agent.pricing")
public record AgentPricingProperties(
        String currency,
        Map<String, ModelPrice> models
) {
    public AgentPricingProperties {
        currency = currency == null || currency.isBlank() ? "CNY" : currency;
        models = models == null ? Map.of() : Map.copyOf(models);
    }

    public record ModelPrice(
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            String version
    ) {
        public ModelPrice {
            inputPerMillion = nonNegative(inputPerMillion);
            outputPerMillion = nonNegative(outputPerMillion);
            version = version == null || version.isBlank() ? "unversioned" : version;
        }

        private static BigDecimal nonNegative(BigDecimal value) {
            if (value == null) {
                return BigDecimal.ZERO;
            }
            if (value.signum() < 0) {
                throw new IllegalArgumentException("Model price must not be negative");
            }
            return value;
        }
    }
}
