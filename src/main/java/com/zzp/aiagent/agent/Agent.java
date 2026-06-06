package com.zzp.aiagent.agent;

import com.zzp.aiagent.advisor.ContentGuardAdvisor;
import com.zzp.aiagent.advisor.ExceptionGuardAdvisor;
import com.zzp.aiagent.advisor.LoggingAdvisor;
import com.zzp.aiagent.utils.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Lightweight Agent wrapper around Spring AI {@link ChatClient}.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Own the {@link ChatClient} lifecycle (tools + advisor chain).</li>
 *   <li>Drive the state machine ({@link AgentState}).</li>
 *   <li>Provide round-level observability via {@link AgentTraceAdvisor}.</li>
 * </ul>
 * <p>
 * Spring AI handles the internal multi-step tool-calling loop automatically.
 * The Agent adds state management, step counting, and structured result
 * reporting on top — it does <b>not</b> re-implement the tool execution loop.
 * <p>
 * For manual ReAct-loop implementation (future learning direction), see
 * {@code yu-ai-agent-master}: {@code ReActAgent} + {@code ToolCallAgent}
 * with {@code internalToolExecutionEnabled=false}.
 */
@Slf4j
public class Agent {

    private final String name;
    private final AgentConfig config;
    private final ChatClient chatClient;

    /**
     * Build an Agent with the standard advisor chain:
     * <pre>
     *   ContentGuard(0) → AgentTrace(5) → MCMA(built-in) → Logging(30) → ExceptionGuard(MAX)
     * </pre>
     *
     * @param name          human-readable identifier
     * @param config        agent configuration
     * @param chatModel     Spring AI ChatModel
     * @param chatMemory    conversation memory
     * @param promptTemplate template engine for system prompt
     * @param tools         one or more {@code @Tool}-annotated POJOs
     */
    public Agent(String name,
                 AgentConfig config,
                 ChatModel chatModel,
                 ChatMemory chatMemory,
                 PromptTemplate promptTemplate,
                 Object... tools) {
        this.name = name;
        this.config = config;

        String systemPrompt = promptTemplate.render("default", "system");

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(tools)
                .defaultAdvisors(
                        new ContentGuardAdvisor(),
                        new AgentTraceAdvisor(config),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new LoggingAdvisor(),
                        new ExceptionGuardAdvisor()
                )
                .build();

        log.info("[Agent] 初始化完成 name={} maxToolCalls={}", name, config.maxToolCalls());
    }

    // ── Non-streaming ───────────────────────────────────────────────

    /**
     * Execute a synchronous agent run.
     *
     * @param userText    user message text (required)
     * @param userMedia   attached image media (nullable)
     * @param toolContext context map forwarded to tool methods
     * @param chatId      conversation identifier for memory
     * @return structured result with state, content, and trace
     */
    public AgentResult run(String userText, Media userMedia,
                           Map<String, Object> toolContext, String chatId) {
        String turnId = turnIdFrom(toolContext);
        AgentContext.init(turnId, config);
        log.debug("[Agent] 开始执行 name={} chatId={} turnId={}", name, chatId, turnId);

        try {
            String content = chatClient.prompt()
                    .user(spec -> {
                        spec.text(userText != null ? userText : "");
                        if (userMedia != null) {
                            spec.media(userMedia);
                        }
                    })
                    .toolContext(toolContext != null ? toolContext : Map.of())
                    .advisors(spec -> spec
                            .param(ChatMemory.CONVERSATION_ID, chatId)
                            .param("chatId", chatId))
                    .call()
                    .content();

            AgentContext.finish(turnId);
            AgentContext.Snapshot trace = AgentContext.snapshot(turnId);
            log.info("[Agent] 执行完成 name={} chatId={} rounds={} elapsed={}ms",
                    name, chatId, trace.totalRounds(), trace.elapsedMs());
            return new AgentResult(AgentState.FINISHED, content, trace);

        } catch (Exception e) {
            AgentContext.error(turnId, e.getMessage());
            AgentContext.Snapshot trace = AgentContext.snapshot(turnId);
            log.error("[Agent] 执行异常 name={} chatId={} rounds={} error={}",
                    name, chatId, trace.totalRounds(), e.getMessage());
            return new AgentResult(AgentState.ERROR, e.getMessage(), trace);

        } finally {
            AgentContext.clear(turnId);
        }
    }

    // ── Streaming ───────────────────────────────────────────────────

    /**
     * Execute a streaming agent run, returning raw {@link ChatClientResponse}
     * events so callers can assemble SSE tool-call / token / done events.
     * <p>
     * Agent lifecycle ({@link AgentContext#init}/{@link AgentContext#clear})
     * is managed automatically.
     *
     * @param userText    user message text (required)
     * @param userMedia   attached image media (nullable)
     * @param toolContext context map forwarded to tool methods
     * @param chatId      conversation identifier for memory
     * @return stream of {@link ChatClientResponse} (tool calls + text tokens)
     */
    public Flux<ChatClientResponse> streamRaw(String userText, Media userMedia,
                                              Map<String, Object> toolContext, String chatId) {
        String turnId = turnIdFrom(toolContext);
        AgentContext.init(turnId, config);
        log.debug("[Agent] 开始流式执行 name={} chatId={} turnId={}", name, chatId, turnId);

        final String capturedTurnId = turnId;
        return chatClient.prompt()
                .user(spec -> {
                    spec.text(userText != null ? userText : "");
                    if (userMedia != null) {
                        spec.media(userMedia);
                    }
                })
                .toolContext(toolContext != null ? toolContext : Map.of())
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, chatId)
                        .param("chatId", chatId))
                .stream()
                .chatClientResponse()
                .doOnComplete(() -> {
                    AgentContext.finish(capturedTurnId);
                    AgentContext.Snapshot trace = AgentContext.snapshot(capturedTurnId);
                    log.info("[Agent] 流式执行完成 name={} chatId={} rounds={} elapsed={}ms",
                            name, chatId, trace.totalRounds(), trace.elapsedMs());
                })
                .doOnError(e -> {
                    AgentContext.error(capturedTurnId, e.getMessage());
                    log.error("[Agent] 流式执行异常 name={} chatId={}", name, chatId, e);
                })
                .doFinally(signal -> AgentContext.clear(capturedTurnId));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static String turnIdFrom(Map<String, Object> toolContext) {
        if (toolContext == null) return null;
        Object value = toolContext.get("turnId");
        return value instanceof String s ? s : null;
    }

    // ── accessors ───────────────────────────────────────────────────

    public String name() {
        return name;
    }

    public AgentConfig config() {
        return config;
    }

    /** Exposed for callers that need direct ChatClient access (e.g. SSE assembly). */
    ChatClient chatClient() {
        return chatClient;
    }
}
