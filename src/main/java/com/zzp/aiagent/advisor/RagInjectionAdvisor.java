package com.zzp.aiagent.advisor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 MessageChatMemoryAdvisor 之后、PromptOptimizeAdvisor 之前注入 RAG 增强文本。
 * <p>
 * 此举确保 MCMA 存储到 ChatMemory 的是干净的原始用户消息，而非 RAG 增强后的长文本。
 * 仅在 adviseContext 中存在 {@link #KEY_RAG_AUGMENTED} 参数时生效（生成模式），
 * 讨论/分析模式不传该参数，advisor 直接透传。
 */
public class RagInjectionAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String KEY_RAG_AUGMENTED = "ragAugmentedText";

    /**
     * Convenience method for callers to set the augmented text parameter
     * without referencing the string constant directly.
     */
    public static void applyAugmentation(ChatClient.AdvisorSpec spec, String augmentedText) {
        if (augmentedText != null && !augmentedText.isBlank()) {
            spec.param(KEY_RAG_AUGMENTED, augmentedText);
        }
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest enhanced = injectRagText(request);
        return chain.nextCall(enhanced);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest enhanced = injectRagText(request);
        return chain.nextStream(enhanced);
    }

    private ChatClientRequest injectRagText(ChatClientRequest request) {
        String augmentedText = (String) request.context().get(KEY_RAG_AUGMENTED);
        if (augmentedText == null || augmentedText.isBlank()) {
            return request;
        }

        UserMessage userMsg = request.prompt().getUserMessage();
        if (userMsg == null) {
            return request;
        }

        // Replace only the last UserMessage (current turn), preserving media attachments
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um) {
                messages.set(i, UserMessage.builder().text(augmentedText).media(um.getMedia()).build());
                break;
            }
        }
        Prompt newPrompt = new Prompt(messages, request.prompt().getOptions());

        Map<String, Object> ctx = new HashMap<>(request.context());
        return request.mutate().prompt(newPrompt).context(ctx).build();
    }

    @Override
    public String getName() {
        return "RagInjection";
    }

    @Override
    public int getOrder() {
        return 15;
    }
}
