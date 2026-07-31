package com.zzp.aiagent.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

/**
 * Enriches Spring AI model observations with billing facts only. It deliberately
 * never reads prompt instructions, generations, tool arguments or completions.
 */
@Component
public class ChatModelCostObservationFilter implements ObservationFilter {

    private final AgentCostCalculator costCalculator;
    private final AgentObservabilityProperties observabilityProperties;
    private final MeterRegistry meterRegistry;

    public ChatModelCostObservationFilter(AgentCostCalculator costCalculator,
                                          AgentObservabilityProperties observabilityProperties,
                                          MeterRegistry meterRegistry) {
        this.costCalculator = costCalculator;
        this.observabilityProperties = observabilityProperties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!observabilityProperties.enabled()
                || !(context instanceof ChatModelObservationContext chatContext)) {
            return context;
        }

        ChatResponse response = chatContext.getResponse();
        String model = resolveModel(chatContext, response);
        ModelUsage modelUsage = resolveUsage(response);
        if (modelUsage.available()) {
            addHigh(context, AgentObservationKeys.High.MODEL_INPUT_TOKENS, modelUsage.inputTokens());
            addHigh(context, AgentObservationKeys.High.MODEL_OUTPUT_TOKENS, modelUsage.outputTokens());
            addHigh(context, AgentObservationKeys.High.MODEL_TOTAL_TOKENS, modelUsage.totalTokens());
        }

        CostEstimate estimate = costCalculator.estimate(model, modelUsage);
        context.addLowCardinalityKeyValue(KeyValue.of(
                AgentObservationKeys.Low.COST_STATUS,
                estimate.status().name().toLowerCase()));
        recordCostMetrics(model, estimate);
        if (estimate.status() == CostEstimate.Status.CALCULATED) {
            addHigh(context, AgentObservationKeys.High.ESTIMATED_COST, estimate.amount());
            addHigh(context, AgentObservationKeys.High.COST_CURRENCY, estimate.currency());
            addHigh(context, AgentObservationKeys.High.PRICING_VERSION, estimate.pricingVersion());
        }
        return context;
    }

    private void recordCostMetrics(String model, CostEstimate estimate) {
        String modelName = model == null || model.isBlank() ? "unknown" : model;
        String status = estimate.status().name().toLowerCase();
        Tags tags = Tags.of(
                "gen_ai.request.model", modelName,
                AgentObservationKeys.Low.COST_STATUS, status);
        meterRegistry.counter("agent.model.cost.estimates", tags).increment();
        if (estimate.status() == CostEstimate.Status.CALCULATED && estimate.amount() != null) {
            meterRegistry.summary("agent.model.estimated.cost", tags)
                    .record(estimate.amount().doubleValue());
        }
    }

    private ModelUsage resolveUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return ModelUsage.unavailable();
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage instanceof EmptyUsage
                || usage.getPromptTokens() == null || usage.getCompletionTokens() == null) {
            return ModelUsage.unavailable();
        }
        return new ModelUsage(
                usage.getPromptTokens().longValue(),
                usage.getCompletionTokens().longValue());
    }

    private String resolveModel(ChatModelObservationContext context, ChatResponse response) {
        if (response != null && response.getMetadata() != null
                && response.getMetadata().getModel() != null
                && !response.getMetadata().getModel().isBlank()) {
            return response.getMetadata().getModel();
        }
        ChatOptions options = context.getRequest() == null ? null : context.getRequest().getOptions();
        return options == null ? null : options.getModel();
    }

    private void addHigh(Observation.Context context, String key, Object value) {
        for (KeyValue existing : context.getHighCardinalityKeyValues()) {
            if (key.equals(existing.getKey())) {
                return;
            }
        }
        context.addHighCardinalityKeyValue(KeyValue.of(key, String.valueOf(value)));
    }
}
