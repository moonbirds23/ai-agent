package com.zzp.aiagent.advisor;

import com.zzp.aiagent.utils.PromptTemplate;
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

public class PromptOptimizeAdvisor implements CallAdvisor, StreamAdvisor {

    private final PromptTemplate promptTemplate;

    public static final String KEY_OPTIMIZED_PROMPT = "optimizedPrompt";
    public static final String KEY_ORIGINAL_INPUT = "originalUserText";

    public PromptOptimizeAdvisor(PromptTemplate promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest enhanced = enhance(request);
        ChatClientResponse response = chain.nextCall(enhanced);
        return attachOptimizedContext(response, getEnhancedText(enhanced));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest enhanced = enhance(request);
        return chain.nextStream(enhanced)
                .map(r -> attachOptimizedContext(r, getEnhancedText(enhanced)));
    }

    private static String getEnhancedText(ChatClientRequest request) {
        UserMessage userMsg = request.prompt().getUserMessage();
        return userMsg != null ? userMsg.getText() : "";
    }

    private ChatClientRequest enhance(ChatClientRequest request) {
        UserMessage userMsg = request.prompt().getUserMessage();
        if (userMsg == null) return request;
        String original = userMsg.getText();
        if (original == null || original.isBlank()) return request;

        String optimized = promptTemplate.render("default", "optimize", "userInput", original);

        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage um) {
                messages.set(i, UserMessage.builder().text(optimized).media(um.getMedia()).build());
            }
        }
        Prompt newPrompt = new Prompt(messages, request.prompt().getOptions());

        Map<String, Object> ctx = new HashMap<>(request.context());
        ctx.put(KEY_ORIGINAL_INPUT, original);
        return request.mutate().prompt(newPrompt).context(ctx).build();
    }

    private ChatClientResponse attachOptimizedContext(ChatClientResponse response, String optimized) {
        Map<String, Object> ctx = new HashMap<>(response.context());
        ctx.put(KEY_OPTIMIZED_PROMPT, optimized);
        return response.mutate().context(ctx).build();
    }

    @Override
    public String getName() {
        return "PromptOptimize";
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
