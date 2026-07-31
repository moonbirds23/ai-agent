package com.zzp.aiagent.observability;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelCostObservationFilterTest {

    @Test
    void enrichesModelSpanWithUsageAndCalculatedCostWithoutContent() {
        ChatModelCostObservationFilter filter = filter(Map.of(
                "glm-test", new AgentPricingProperties.ModelPrice(
                        new BigDecimal("2"), new BigDecimal("4"), "2026-07")
        ));
        ChatModelObservationContext context = context("do-not-export-this-prompt", "glm-request");
        context.setResponse(response("glm-test", new DefaultUsage(1_000_000, 500_000)));

        filter.map(context);

        assertThat(value(context, AgentObservationKeys.High.MODEL_INPUT_TOKENS)).isEqualTo("1000000");
        assertThat(value(context, AgentObservationKeys.High.MODEL_OUTPUT_TOKENS)).isEqualTo("500000");
        assertThat(value(context, AgentObservationKeys.High.MODEL_TOTAL_TOKENS)).isEqualTo("1500000");
        assertThat(value(context, AgentObservationKeys.High.ESTIMATED_COST)).isEqualTo("4");
        assertThat(value(context, AgentObservationKeys.High.COST_CURRENCY)).isEqualTo("CNY");
        assertThat(value(context, AgentObservationKeys.High.PRICING_VERSION)).isEqualTo("2026-07");
        assertThat(lowValue(context, AgentObservationKeys.Low.COST_STATUS)).isEqualTo("calculated");
        assertThat(allValues(context)).doesNotContain("do-not-export-this-prompt");
    }

    @Test
    void marksUsageMissingWithoutWritingFakeTokenValues() {
        ChatModelCostObservationFilter filter = filter(Map.of());
        ChatModelObservationContext context = context("secret", "glm-test");

        filter.map(context);

        assertThat(lowValue(context, AgentObservationKeys.Low.COST_STATUS)).isEqualTo("usage_missing");
        assertThat(value(context, AgentObservationKeys.High.MODEL_TOTAL_TOKENS)).isNull();
        assertThat(value(context, AgentObservationKeys.High.ESTIMATED_COST)).isNull();
    }

    @Test
    void marksPriceMissingWhileKeepingProviderUsage() {
        ChatModelCostObservationFilter filter = filter(Map.of());
        ChatModelObservationContext context = context("secret", "unknown-model");
        context.setResponse(response(null, new DefaultUsage(10, 20)));

        filter.map(context);

        assertThat(lowValue(context, AgentObservationKeys.Low.COST_STATUS)).isEqualTo("price_missing");
        assertThat(value(context, AgentObservationKeys.High.MODEL_TOTAL_TOKENS)).isEqualTo("30");
        assertThat(value(context, AgentObservationKeys.High.ESTIMATED_COST)).isNull();
    }

    @Test
    void keepsExistingSpringAiUsageAttributeWithoutAddingDuplicate() {
        ChatModelCostObservationFilter filter = filter(Map.of());
        ChatModelObservationContext context = context("secret", "glm-test");
        context.addHighCardinalityKeyValue(
                KeyValue.of(AgentObservationKeys.High.MODEL_INPUT_TOKENS, "spring-ai-value"));
        context.setResponse(response("glm-test", new DefaultUsage(10, 20)));

        filter.map(context);

        assertThat(context.getHighCardinalityKeyValues().stream()
                .filter(item -> item.getKey().equals(AgentObservationKeys.High.MODEL_INPUT_TOKENS)))
                .singleElement()
                .extracting(KeyValue::getValue)
                .isEqualTo("spring-ai-value");
    }

    private ChatModelCostObservationFilter filter(
            Map<String, AgentPricingProperties.ModelPrice> prices) {
        AgentCostCalculator calculator = new AgentCostCalculator(
                new AgentObservabilityProperties(true, true),
                new AgentPricingProperties("CNY", prices));
        AgentObservabilityProperties properties = new AgentObservabilityProperties(true, true);
        return new ChatModelCostObservationFilter(
                calculator, properties, new SimpleMeterRegistry());
    }

    private ChatModelObservationContext context(String promptText, String model) {
        ChatOptions options = ChatOptions.builder().model(model).build();
        return ChatModelObservationContext.builder()
                .prompt(new Prompt(promptText, options))
                .provider("zhipu")
                .build();
    }

    private ChatResponse response(String model, DefaultUsage usage) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder().usage(usage);
        if (model != null) {
            builder.model(model);
        }
        ChatResponseMetadata metadata = builder.build();
        return new ChatResponse(List.of(), metadata);
    }

    private String value(ChatModelObservationContext context, String key) {
        return context.getHighCardinalityKeyValues().stream()
                .filter(item -> item.getKey().equals(key))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse(null);
    }

    private String lowValue(ChatModelObservationContext context, String key) {
        return context.getLowCardinalityKeyValues().stream()
                .filter(item -> item.getKey().equals(key))
                .map(KeyValue::getValue)
                .findFirst()
                .orElse(null);
    }

    private String allValues(ChatModelObservationContext context) {
        return context.getLowCardinalityKeyValues().toString()
                + context.getHighCardinalityKeyValues();
    }
}
