package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.AgentConfig;
import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.AgentTraceAdvisor;
import com.zzp.aiagent.advisor.ContentGuardAdvisor;
import com.zzp.aiagent.advisor.ExceptionGuardAdvisor;
import com.zzp.aiagent.advisor.LoggingAdvisor;
import com.zzp.aiagent.utils.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.List;
import com.zzp.aiagent.tool.GalleryAgentTools;
import com.zzp.aiagent.tool.PexelsSearchTools;
import com.zzp.aiagent.tool.WebSearchTools;
import com.zzp.aiagent.agent.task.TaskStep;

@Slf4j
@Component
@Profile("!test")
public class SpringAiAutoToolExecutor implements AgentExecutor {

    private final ChatClient chatClient;

    public SpringAiAutoToolExecutor(ChatModel chatModel,
                                     PromptTemplate promptTemplate,
                                     GalleryAgentTools galleryAgentTools,
                                     WebSearchTools webSearchTools,
                                     PexelsSearchTools pexelsSearchTools) {
        String systemPrompt = promptTemplate.render("default", "system");
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(galleryAgentTools, webSearchTools, pexelsSearchTools)
                .defaultAdvisors(
                        new ContentGuardAdvisor(),
                        new AgentTraceAdvisor(AgentConfig.of("auto-executor")),
                        new LoggingAdvisor(),
                        new ExceptionGuardAdvisor()
                )
                .build();
    }

    @Override
    public AgentResult execute(AgentInput input) {
        log.debug("[AutoExecutor] execute chatId={}", input.chatId());
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt();
            if (!input.memoryMessages().isEmpty()) {
                request.messages(input.memoryMessages());
            }
            request = requireValidatedToolCall(request, input);
            String content = request
                    .user(spec -> {
                        spec.text(input.modelInput());
                        if (input.userMedia() != null) {
                            spec.media(input.userMedia());
                        }
                    })
                    .toolContext(input.toolContext() != null ? input.toolContext() : Map.of())
                    .advisors(spec -> {
                        spec.param("chatId", input.chatId());
                        if (input.plan() != null) {
                            spec.param("turnId", input.plan().turnId());
                        }
                    })
                    .call()
                    .content();
            return new AgentResult(AgentState.FINISHED, content, null);
        } catch (Exception e) {
            log.error("[AutoExecutor] error chatId={}: {}", input.chatId(), e.getMessage());
            return new AgentResult(AgentState.ERROR, e.getMessage(), null);
        }
    }

    /**
     * A validated plan with exactly one required tool is an execution contract,
     * not a suggestion. Requiring a tool call at the model API prevents a model
     * from returning plausible prose without producing backend-verifiable
     * evidence. Multi-tool and optional-tool plans keep the model default.
     */
    private ChatClient.ChatClientRequestSpec requireValidatedToolCall(
            ChatClient.ChatClientRequestSpec request, AgentInput input) {
        if (input.plan() == null || input.plan().steps() == null) {
            return request;
        }
        List<String> requiredTools = input.plan().steps().stream()
                .filter(TaskStep::required)
                .map(TaskStep::toolName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (requiredTools.size() != 1) {
            return request;
        }
        return request.options(OpenAiChatOptions.builder()
                .toolChoice("required")
                .build());
    }

    @Override
    public Flux<ChatClientResponse> stream(AgentInput input) {
        log.debug("[AutoExecutor] stream chatId={}", input.chatId());
        var request = chatClient.prompt();
        if (!input.memoryMessages().isEmpty()) {
            request.messages(input.memoryMessages());
        }
        return request
                .user(spec -> {
                    spec.text(input.modelInput());
                    if (input.userMedia() != null) {
                        spec.media(input.userMedia());
                    }
                })
                .toolContext(input.toolContext() != null ? input.toolContext() : Map.of())
                .advisors(spec -> {
                    spec.param("chatId", input.chatId());
                    if (input.plan() != null) {
                        spec.param("turnId", input.plan().turnId());
                    }
                })
                .stream()
                .chatClientResponse()
                .doOnComplete(() -> log.debug("[AutoExecutor] stream complete chatId={}", input.chatId()))
                .doOnError(e -> log.error("[AutoExecutor] stream error chatId={}", input.chatId(), e));
    }
}
