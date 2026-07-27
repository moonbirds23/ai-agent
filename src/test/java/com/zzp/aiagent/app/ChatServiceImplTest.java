package com.zzp.aiagent.app;

import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.WorkflowEngine;
import com.zzp.aiagent.agent.executor.AgentExecutor;
import com.zzp.aiagent.agent.prompt.PromptBudgetManager;
import com.zzp.aiagent.agent.prompt.PromptProperties;
import com.zzp.aiagent.agent.task.RecoveryPolicy;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskPromptBuilder;
import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.memory.MemoryContextBuilder;
import com.zzp.aiagent.memory.MemoryWriter;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.observability.AgentObservabilityProperties;
import com.zzp.aiagent.observability.AgentObservationKeys;
import com.zzp.aiagent.observability.AgentObservationNames;
import com.zzp.aiagent.observability.AgentTelemetry;
import com.zzp.aiagent.service.ChatMediaService;
import com.zzp.aiagent.service.ConversationLimitService;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.service.RagService;
import com.zzp.aiagent.service.impl.ChatServiceImpl;
import com.zzp.aiagent.service.impl.PromptReferenceAssembler;
import com.zzp.aiagent.tool.CurrentImageContext;
import com.zzp.aiagent.tool.ToolProgressContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.tracing.Tracer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplTest {

    private AgentExecutor executor;
    private WorkflowEngine workflowEngine;
    private ChatServiceImpl chatService;
    private TestObservationRegistry observationRegistry;
    private SimpleMeterRegistry meterRegistry;
    private RagService ragService;
    private TaskLedger ledger;

    @BeforeEach
    void setUp() {
        GalleryService galleryService = mock(GalleryService.class);
        PictureAiProfileService profileService = mock(PictureAiProfileService.class);
        ChatMediaService mediaService = mock(ChatMediaService.class);
        ConversationLimitService limitService = mock(ConversationLimitService.class);
        CurrentImageContext imageContext = new CurrentImageContext();
        ToolProgressContext progressContext = new ToolProgressContext();
        ledger = new TaskLedger();
        workflowEngine = mock(WorkflowEngine.class);
        MemoryContextBuilder memoryContextBuilder = mock(MemoryContextBuilder.class);
        MemoryWriter memoryWriter = mock(MemoryWriter.class);
        ragService = mock(RagService.class);
        PromptReferenceAssembler promptReferenceAssembler = mock(PromptReferenceAssembler.class);
        executor = mock(AgentExecutor.class);

        when(workflowEngine.plan(any(ChatRequest.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String turnId = invocation.getArgument(2);
                    TaskPlan plan = TaskPlan.chat(turnId,
                            invocation.<ChatRequest>getArgument(0).message());
                    // The real WorkflowEngine.plan() registers the plan with the ledger.
                    ledger.startPlan(plan);
                    return plan;
                });
        when(workflowEngine.selectExecutor(any(TaskPlan.class))).thenReturn(executor);
        when(workflowEngine.isManualExecution(any(TaskPlan.class))).thenReturn(false);
        when(workflowEngine.execute(any(), any(TaskPlan.class))).thenReturn(
                new AgentResult(AgentState.FINISHED, "你好！有什么可以帮你的？", null));
        when(memoryContextBuilder.build(anyString())).thenReturn(List.of());
        when(executor.execute(any())).thenReturn(
                new AgentResult(AgentState.FINISHED, "你好！有什么可以帮你的？", null));

        observationRegistry = TestObservationRegistry.create();
        meterRegistry = new SimpleMeterRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        AgentTelemetry telemetry = new AgentTelemetry(
                observationRegistry,
                meterRegistry,
                beanFactory.getBeanProvider(Tracer.class),
                new AgentObservabilityProperties(true, false));

        chatService = new ChatServiceImpl(
                galleryService,
                profileService,
                mediaService,
                limitService,
                imageContext,
                progressContext,
                ledger,
                new RecoveryPolicy(),
                workflowEngine,
                memoryContextBuilder,
                memoryWriter,
                new TaskPromptBuilder(),
                new PromptBudgetManager(new PromptProperties(1500, 1200, 2500, 2500)),
                ragService,
                promptReferenceAssembler,
                telemetry);
    }

    @Test
    void chatReturnsExecutorResponse() {
        ChatResponseVO result = chatService.chat(
                new ChatRequest("你好", null, null, null, null), "chat-1");

        assertThat(result.type()).isEqualTo("chat");
        assertThat(result.message()).contains("你好");
    }

    @Test
    void chatDoesNotTreatUnverifiedGenerationAsSuccess() {
        when(workflowEngine.execute(any(), any(TaskPlan.class))).thenReturn(
                new AgentResult(AgentState.FINISHED, "图片已经生成", null));

        ChatResponseVO result = chatService.chat(
                new ChatRequest("生成图片", null, null, null, null), "chat-2");

        assertThat(result.type()).isEqualTo("chat");
    }

    @Test
    void generationPlanBuildsRagContextWithResolvedChatId() {
        when(workflowEngine.plan(any(ChatRequest.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String turnId = invocation.getArgument(2);
                    TaskPlan plan = new TaskPlan(
                            turnId,
                            TaskType.IMAGE_GENERATION,
                            invocation.<ChatRequest>getArgument(0).message(),
                            List.of(TaskStep.of("generate", "generate", true, "generateImage")),
                            false,
                            true,
                            false,
                            Map.of());
                    ledger.startPlan(plan);
                    return plan;
                });
        when(ragService.buildContext(any(ChatRequest.class)))
                .thenReturn(com.zzp.aiagent.domain.rag.RagContext.empty());

        chatService.chat(new ChatRequest("generate", null, null, null, null), "rag-chat");

        verify(ragService).buildContext(
                org.mockito.ArgumentMatchers.argThat(request -> "rag-chat".equals(request.chatId())));
    }

    @Test
    void plainChatDoesNotInvokeRagService() {
        chatService.chat(new ChatRequest("hello", null, null, null, null), "plain-chat");

        verify(ragService, never()).buildContext(any(ChatRequest.class));
    }

    @Test
    void streamEmitsLifecycleAndTokens() {
        when(workflowEngine.stream(any(AgentExecutor.class), any(), any(TaskPlan.class))).thenReturn(Flux.just(
                response("H"), response("i")));

        StepVerifier.create(chatService.chatStream(
                        new ChatRequest("hello", null, null, null, null), "stream-1"))
                .expectNextMatches(event -> "chatId".equals(event.type()))
                .expectNextMatches(event -> "task_planned".equals(event.type()))
                .expectNextMatches(event -> "token".equals(event.type()))
                .expectNextMatches(event -> "token".equals(event.type()))
                .expectNextMatches(event -> "task_verified".equals(event.type()))
                .expectNextMatches(event -> "done".equals(event.type()))
                .verifyComplete();

        observationRegistry.assertThat()
                .hasNumberOfObservationsWithNameEqualTo(AgentObservationNames.TURN, 1)
                .hasAnObservationWithAKeyValue(AgentObservationKeys.Low.OUTCOME, "success");
        assertThat(meterRegistry.get("agent.response.time.to.first.token").timer().count())
                .isEqualTo(1);
    }

    @Test
    void streamErrorReturnsStructuredFailure() {
        when(workflowEngine.stream(any(AgentExecutor.class), any(), any(TaskPlan.class)))
                .thenReturn(Flux.error(new RuntimeException("network")));

        StepVerifier.create(chatService.chatStream(
                        new ChatRequest("hello", null, null, null, null), "stream-2"))
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(event -> true)
                .consumeRecordedWith(events -> assertThat(
                        events.stream().map(event -> event.type()).toList()).contains("error"))
                .verifyComplete();

        observationRegistry.assertThat()
                .hasAnObservationWithAKeyValue(AgentObservationKeys.Low.OUTCOME, "error");
    }

    @Test
    void streamCancellationClosesTurnAsCancelled() {
        when(workflowEngine.stream(any(AgentExecutor.class), any(), any(TaskPlan.class)))
                .thenReturn(Flux.never());

        StepVerifier.create(chatService.chatStream(
                        new ChatRequest("hello", null, null, null, null), "stream-cancel"))
                .expectNextCount(2)
                .thenCancel()
                .verify();

        observationRegistry.assertThat()
                .hasNumberOfObservationsWithNameEqualTo(AgentObservationNames.TURN, 1)
                .hasAnObservationWithAKeyValue(AgentObservationKeys.Low.OUTCOME, "cancelled");
    }

    private static ChatClientResponse response(String text) {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(response, Map.of());
    }
}
