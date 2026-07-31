package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.WorkflowEngine;
import com.zzp.aiagent.agent.executor.AgentExecutor;
import com.zzp.aiagent.agent.executor.AgentInput;
import com.zzp.aiagent.agent.prompt.PromptBudgetManager;
import com.zzp.aiagent.agent.task.RecoveryPolicy;
import com.zzp.aiagent.agent.task.ResponseComposer;
import com.zzp.aiagent.agent.task.StepStatus;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskPromptBuilder;
import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.TaskVerifier;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.agent.task.VerificationResult;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.domain.rag.RagContext;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.entity.PictureAiProfile;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.ImageGeneratedEventVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import com.zzp.aiagent.memory.MemoryContextBuilder;
import com.zzp.aiagent.memory.MemoryWriter;
import com.zzp.aiagent.observability.AgentObservationKeys;
import com.zzp.aiagent.observability.AgentObservationNames;
import com.zzp.aiagent.observability.AgentTelemetry;
import com.zzp.aiagent.observability.DemoCaseContext;
import com.zzp.aiagent.service.ChatMediaService;
import com.zzp.aiagent.service.ChatService;
import com.zzp.aiagent.service.ConversationLimitService;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.service.RagService;
import com.zzp.aiagent.tool.CurrentImageContext;
import com.zzp.aiagent.tool.ToolProgressContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat service — orchestration role.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Pre-processing: image auto-save, media construction, reference context.</li>
 *   <li>Delegate execution to the selected AgentExecutor.</li>
 *   <li>Post-processing: hallucination guard, VO construction.</li>
 * </ul>
 * Advisor chain (managed by Agent):
 * ContentGuard(0) → AgentTrace(5) → MCMA(built-in) → Logging(30) → ExceptionGuard(MAX)
 */
@Service
@Profile("!test")
@Slf4j
public class ChatServiceImpl implements ChatService {

    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("png", "jpeg", "jpg", "webp", "gif");
    private static final Pattern PSEUDO_SEARCH_GALLERY =
            Pattern.compile("^\\s*searchGallery\\([\"“](.*?)[\"”]\\)\\s*$");

    private final GalleryService galleryService;
    private final PictureAiProfileService pictureAiProfileService;
    private final ChatMediaService chatMediaService;
    private final ConversationLimitService conversationLimitService;
    private final CurrentImageContext currentImageContext;
    private final ToolProgressContext toolProgressContext;
    private final TaskLedger taskLedger;
    private final RecoveryPolicy recoveryPolicy;
    private final WorkflowEngine workflowEngine;
    private final MemoryContextBuilder memoryContextBuilder;
    private final MemoryWriter memoryWriter;
    private final TaskPromptBuilder taskPromptBuilder;
    private final PromptBudgetManager promptBudgetManager;
    private final RagService ragService;
    private final PromptReferenceAssembler promptReferenceAssembler;
    private final AgentTelemetry telemetry;

    public ChatServiceImpl(GalleryService galleryService,
                           PictureAiProfileService pictureAiProfileService,
                           ChatMediaService chatMediaService,
                           ConversationLimitService conversationLimitService,
                           CurrentImageContext currentImageContext,
                           ToolProgressContext toolProgressContext,
                           TaskLedger taskLedger,
                           RecoveryPolicy recoveryPolicy,
                           WorkflowEngine workflowEngine,
                           MemoryContextBuilder memoryContextBuilder,
                           MemoryWriter memoryWriter,
                           TaskPromptBuilder taskPromptBuilder,
                           PromptBudgetManager promptBudgetManager,
                           RagService ragService,
                           PromptReferenceAssembler promptReferenceAssembler,
                           AgentTelemetry telemetry) {
        this.galleryService = galleryService;
        this.pictureAiProfileService = pictureAiProfileService;
        this.chatMediaService = chatMediaService;
        this.conversationLimitService = conversationLimitService;
        this.currentImageContext = currentImageContext;
        this.toolProgressContext = toolProgressContext;
        this.taskLedger = taskLedger;
        this.recoveryPolicy = recoveryPolicy;
        this.workflowEngine = workflowEngine;
        this.memoryContextBuilder = memoryContextBuilder;
        this.memoryWriter = memoryWriter;
        this.taskPromptBuilder = taskPromptBuilder;
        this.promptBudgetManager = promptBudgetManager;
        this.ragService = ragService;
        this.promptReferenceAssembler = promptReferenceAssembler;
        this.telemetry = telemetry;
    }

    // ── 非流式入口 ──────────────────────────────────────────────────

    @Override
    public ChatResponseVO chat(ChatRequest request, String chatId) {
        String turnId = newTurnId(chatId);
        AgentTelemetry.AgentObservation turn = turnObservation(chatId, turnId, request);
        try (Observation.Scope ignored = turn.openScope()) {
            return chatObserved(request, chatId, turnId, turn);
        } catch (RuntimeException e) {
            turn.error(e);
            turn.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error");
            throw e;
        } finally {
            turn.stop();
        }
    }

    private ChatResponseVO chatObserved(ChatRequest request, String chatId, String turnId,
                                        AgentTelemetry.AgentObservation turn) {
        conversationLimitService.checkLimit(chatId);
        GalleryPicture saved = autoSaveToCacheGallery(request);
        TaskPlan plan = workflowEngine.plan(request, chatId, turnId);
        turn.lowCardinality(AgentObservationKeys.Low.TASK_TYPE,
                plan.taskType().name().toLowerCase(java.util.Locale.ROOT));
        currentImageContext.bind(turnId, extractImageBase64(request));

        try {
            String rawUserText = request.message() != null ? request.message() : "";
            String executionContext = buildExecutionContext(request, plan, chatId);
            Media media = chatMediaService.createMedia(saved, request.imageBase64(), request.imageUrl());
            Map<String, Object> ctx = toolContext(chatId, turnId, request);
            ctx.put("imageBase64", extractImageBase64(request) != null ? extractImageBase64(request) : "");
            AgentInput input = AgentInput.of(rawUserText, executionContext,
                    memoryContextBuilder.build(chatId), media, ctx, chatId, plan);

            AgentResult result = workflowEngine.execute(input, plan);
            String response = result.state() == AgentState.ERROR ? result.content() : result.content();
            String effectiveResponse = executePlannedFallbackIfNeeded(plan, turnId, response);
            String safeResponse = verifyComposeAndRecover(
                    effectiveResponse, turnId, request, plan);
            VerificationResult verification = taskLedger.getVerification(turnId);
            turn.lowCardinality(AgentObservationKeys.Low.OUTCOME,
                            result.state() == AgentState.ERROR ? "error" : "success")
                    .lowCardinality(AgentObservationKeys.Low.TASK_OUTCOME,
                            verification != null && verification.deliverable()
                                    ? "completed" : "failed");
            writeTrustedMemoryObserved(chatId, turnId, rawUserText, safeResponse, verification,
                    request.referencePictureIds());

            ImageGeneratedEventVO imageData = toolProgressContext.getGeneratedImage(turnId);
            if (verification != null && verification.deliverable() && imageData != null) {
                return ChatResponseVO.imageGenerated(chatId, imageData.imageUrl(),
                        imageData.imageBase64(), safeResponse);
            }
            return responseForVerification(chatId, safeResponse, verification);
        } finally {
            toolProgressContext.clear(turnId);
            taskLedger.clear(turnId);
            currentImageContext.clear(turnId);
        }
    }

    // ── 流式入口 ────────────────────────────────────────────────────

    @Override
    public Flux<StreamEventVO> chatStream(ChatRequest request, String chatId) {
        return Flux.defer(() -> {
            String turnId = newTurnId(chatId);
            AgentTelemetry.AgentObservation turn = turnObservation(chatId, turnId, request);
            long startedNanos = System.nanoTime();
            AtomicBoolean firstToken = new AtomicBoolean();
            AtomicBoolean failed = new AtomicBoolean();
            Flux<StreamEventVO> stream;
            try (Observation.Scope ignored = turn.openScope()) {
                stream = chatStreamObserved(request, chatId, turnId, turn, failed);
            } catch (RuntimeException e) {
                turn.error(e);
                turn.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error");
                turn.stop();
                return Flux.error(e);
            }
            return stream
                    .doOnNext(event -> {
                        if ("token".equals(event.type()) && firstToken.compareAndSet(false, true)) {
                            Duration ttft = Duration.ofNanos(System.nanoTime() - startedNanos);
                            turn.highCardinality(
                                            AgentObservationKeys.High.HTTP_TIME_TO_FIRST_SSE_TOKEN_MS,
                                            ttft.toMillis())
                                    .event("agent.http.first_sse_token");
                            telemetry.record("agent.response.time.to.first.token", ttft,
                                    AgentObservationKeys.Low.OUTCOME, "success");
                            telemetry.record("agent.http.time.to.first.sse.token", ttft,
                                    AgentObservationKeys.Low.OUTCOME, "success");
                        }
                    })
                    .doOnError(error -> {
                        failed.set(true);
                        turn.error(error);
                    })
                    .doFinally(signal -> finishStreamTurn(turn, signal, failed.get()))
                    .contextWrite(context -> context.put(
                            ObservationThreadLocalAccessor.KEY, turn.observation()));
        });
    }

    private Flux<StreamEventVO> chatStreamObserved(ChatRequest request, String chatId, String turnId,
                                                    AgentTelemetry.AgentObservation turn,
                                                    AtomicBoolean failed) {
        conversationLimitService.checkLimit(chatId);
        GalleryPicture saved = autoSaveToCacheGallery(request);
        TaskPlan plan = workflowEngine.plan(request, chatId, turnId);
        turn.lowCardinality(AgentObservationKeys.Low.TASK_TYPE,
                plan.taskType().name().toLowerCase(java.util.Locale.ROOT));
        currentImageContext.bind(turnId, extractImageBase64(request));

        String rawUserText = request.message() != null ? request.message() : "";
        String executionContext = buildExecutionContext(request, plan, chatId);
        Media media = chatMediaService.createMedia(saved, request.imageBase64(), request.imageUrl());
        Map<String, Object> ctx = toolContext(chatId, turnId, request);
        ctx.put("imageBase64", extractImageBase64(request) != null ? extractImageBase64(request) : "");
        AgentInput input = AgentInput.of(rawUserText, executionContext,
                memoryContextBuilder.build(chatId), media, ctx, chatId, plan);
        AgentExecutor executor = workflowEngine.selectExecutor(plan);

        StringBuilder accumulator = new StringBuilder();
        Sinks.Many<StreamEventVO> progressSink = Sinks.many().multicast().onBackpressureBuffer();
        toolProgressContext.bind(turnId, chatId, progressSink);

        Flux<StreamEventVO> eventFlux = workflowEngine.isManualExecution(plan)
                ? manualExecutionEvents(executor, input, accumulator, chatId, turnId, plan)
                : workflowEngine.stream(executor, input, plan)
                .concatMap(clientResponse -> {
                    Flux<StreamEventVO> events = Flux.empty();
                    ChatResponse cr = clientResponse.chatResponse();
                    if (cr == null) return events;

                    // Tool call signal — the model wants to invoke a function
                    if (cr.hasToolCalls()) {
                        List<AssistantMessage.ToolCall> calls = cr.getResult().getOutput().getToolCalls();
                        if (calls != null) {
                            for (var tc : calls) {
                                String label = toolLabel(tc.name());
                                log.info("[Stream] 工具调用 chatId={} tool={} argumentLength={}",
                                        chatId, tc.name(),
                                        tc.arguments() != null ? tc.arguments().length() : 0);
                                events = events.concatWith(
                                        Flux.just(StreamEventVO.toolCall(tc.name(), label, chatId)));
                            }
                        }
                    }

                    // Text content — regular token
                    String text = cr.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        accumulator.append(text);
                        events = events.concatWith(Flux.just(StreamEventVO.token(text)));
                    }

                    return events;
                });

        Flux<StreamEventVO> doneEvent = Flux.defer(() -> {
            String fullText = accumulator.toString();
            String effectiveText = executePlannedFallbackIfNeeded(plan, turnId, fullText);
            String safeText = verifyComposeAndRecover(effectiveText, turnId, request, plan);
            VerificationResult verification = taskLedger.getVerification(turnId);
            writeTrustedMemoryObserved(chatId, turnId, rawUserText, safeText, verification,
                    request.referencePictureIds());
            var snapshot = taskLedger.snapshot(turnId, recoveryPolicy);
            List<StreamEventVO> events = new java.util.ArrayList<>();
            events.add(StreamEventVO.taskVerified(chatId, snapshot));
            if (snapshot.recoveryAction() != null
                    && snapshot.recoveryAction().type()
                    != com.zzp.aiagent.agent.task.RecoveryActionType.NONE) {
                events.add(StreamEventVO.recoverySuggested(
                        chatId, snapshot.recoveryAction().message()));
            }
            events.add(StreamEventVO.done(
                    chatId, responseForVerification(chatId, safeText, verification)));
            return Flux.fromIterable(events);
        });

        Flux<StreamEventVO> mergedEvents = Flux.merge(
                progressSink.asFlux(),
                eventFlux.doFinally(signal -> toolProgressContext.complete(turnId))
        );

        return Flux.concat(
                Flux.just(StreamEventVO.chatId(chatId)),
                Flux.just(StreamEventVO.taskPlanned(chatId, taskLedger.snapshot(turnId, recoveryPolicy))),
                mergedEvents,
                doneEvent
        ).onErrorResume(e -> {
            failed.set(true);
            turn.error(e);
            String errorMsg;
            if (e instanceof BusinessException be) {
                log.warn("[Stream] 业务异常 chatId={} code={}", chatId, be.getCode());
                errorMsg = be.getMessage();
            } else {
                log.error("[Stream] 未知异常 chatId={}", chatId, e);
                errorMsg = "处理异常，请重试";
            }
            taskLedger.markVerifying(turnId);
            taskLedger.completeVerification(turnId, VerificationResult.failed(errorMsg));
            return Flux.just(
                    StreamEventVO.taskVerified(chatId, taskLedger.snapshot(turnId, recoveryPolicy)),
                    StreamEventVO.error(errorMsg));
        }).doFinally(signal -> {
            toolProgressContext.clear(turnId);
            taskLedger.clear(turnId);
            currentImageContext.clear(turnId);
        });
    }

    private Flux<StreamEventVO> manualExecutionEvents(AgentExecutor executor, AgentInput input,
                                                       StringBuilder accumulator, String chatId,
                                                       String turnId, TaskPlan plan) {
        return Flux.defer(() -> {
            AgentResult result = workflowEngine.execute(executor, input, plan);
            String content = result.content() != null ? result.content() : "";
            accumulator.append(content);

            List<StreamEventVO> events = new java.util.ArrayList<>();
            for (var step : plan.steps()) {
                if (step.toolName() == null) continue;
                StepStatus status = taskLedger.getStepStatus(turnId, step.code());
                events.add(StreamEventVO.taskStepStarted(chatId, step.code(), step.description()));
                if (status == StepStatus.SUCCESS) {
                    events.add(StreamEventVO.taskStepCompleted(chatId, step.code(), step.description()));
                } else if (status == StepStatus.FAILED) {
                    events.add(StreamEventVO.taskStepFailed(chatId, step.code(), "步骤执行失败"));
                }
            }
            if (!content.isBlank()) {
                events.add(StreamEventVO.token(content));
            }
            return Flux.fromIterable(events);
        });
    }

    private String buildExecutionContext(ChatRequest request, TaskPlan plan, String chatId) {
        String taskPrompt = promptBudgetManager.trimTask(taskPromptBuilder.build(plan));
        String referenceContext = promptBudgetManager.trimRag(buildRagContext(request, plan, chatId));
        if (taskPrompt.isBlank()) return referenceContext;
        if (referenceContext.isBlank()) return taskPrompt;
        return taskPrompt + "\n\n" + referenceContext;
    }

    private String buildRagContext(ChatRequest request, TaskPlan plan, String chatId) {
        if (!usesGenerationRag(plan)) {
            return buildSelectedReferenceContext(request.referencePictureIds());
        }
        ChatRequest normalized = withChatId(request, chatId);
        try {
            RagContext context = ragService.buildContext(normalized);
            if (context == null || context.isEmpty()) {
                return "";
            }
            return promptReferenceAssembler.assemble("本轮用户需求见后文。", context);
        } catch (RuntimeException error) {
            log.warn("[RAG] 上下文构建失败，降级为显式参考图 metadata errorType={}",
                    error.getClass().getName());
            return buildSelectedReferenceContext(request.referencePictureIds());
        }
    }

    private static boolean usesGenerationRag(TaskPlan plan) {
        return plan != null && (plan.taskType() == TaskType.IMAGE_GENERATION
                || plan.taskType() == TaskType.CREATIVE_WORKFLOW);
    }

    private static ChatRequest withChatId(ChatRequest request, String chatId) {
        return new ChatRequest(
                request.message(),
                chatId,
                request.generationMode(),
                request.imageBase64(),
                request.imageUrl(),
                request.mode(),
                request.referencePictureIds(),
                request.useGalleryRag(),
                request.referenceMode(),
                request.styleTemplateCode(),
                request.saveGeneratedToGallery());
    }

    private void writeTrustedMemoryObserved(String chatId, String turnId, String rawUserText,
                                            String safeResponse, VerificationResult verification,
                                            List<Long> referenceIds) {
        AgentTelemetry.AgentObservation observation = telemetry.start(AgentObservationNames.MEMORY_WRITE)
                .highCardinality(AgentObservationKeys.High.CHAT_ID, chatId)
                .highCardinality(AgentObservationKeys.High.TURN_ID, turnId);
        try (Observation.Scope ignored = observation.openScope()) {
            writeTrustedMemoryInternal(chatId, rawUserText, safeResponse, verification, referenceIds);
            boolean written = verification != null && verification.deliverable();
            observation.lowCardinality(AgentObservationKeys.Low.OUTCOME,
                            written ? "written" : "skipped")
                    .lowCardinality(AgentObservationKeys.Low.MEMORY_WRITE, written)
                    .lowCardinality(AgentObservationKeys.Low.MEMORY_WRITE_REASON,
                            written ? "verified" : "verification_failed")
                    .highCardinality(AgentObservationKeys.High.MEMORY_MESSAGE_COUNT,
                            written ? 2 : 0);
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private void writeTrustedMemoryInternal(String chatId, String rawUserText, String safeResponse,
                                    VerificationResult verification, List<Long> referenceIds) {
        memoryWriter.writeVerifiedTurn(chatId, rawUserText, safeResponse, verification);
        if (verification != null && verification.deliverable()
                && referenceIds != null && !referenceIds.isEmpty()) {
            memoryWriter.writeResourceSummary(chatId,
                    "本轮使用图库图片 ID：" + referenceIds);
        }
    }

    private String verifyComposeAndRecover(String modelResponse, String turnId,
                                           ChatRequest request, TaskPlan plan) {
        AgentTelemetry.AgentObservation verifier = telemetry.start(AgentObservationNames.VERIFIER)
                .highCardinality(AgentObservationKeys.High.TURN_ID, turnId);
        String composed;
        VerificationResult verification;
        try (Observation.Scope ignored = verifier.openScope()) {
            composed = ResponseComposer.composeVerified(modelResponse, taskLedger, turnId);
            verification = taskLedger.getVerification(turnId);
            List<ToolExecutionRecord> records = taskLedger.getRecords(turnId);
            boolean noSaveRequested = Boolean.FALSE.equals(request.saveGeneratedToGallery())
                    || containsNoSaveRequest(request.message());
            VerificationResult constrained = TaskVerifier.enforceNoSaveConstraint(
                    verification, noSaveRequested, records);
            if (constrained != verification) {
                verification = constrained;
                taskLedger.completeVerification(turnId, verification);
                composed = ResponseComposer.compose(modelResponse, verification);
            }
            long requiredSteps = plan.steps().stream()
                    .filter(TaskStep::required)
                    .filter(step -> step.toolName() != null)
                    .count();
            long completedRequiredSteps = plan.steps().stream()
                    .filter(TaskStep::required)
                    .filter(step -> step.toolName() != null)
                    .filter(step -> records.stream().anyMatch(record ->
                            record.success() && step.toolName().equals(record.toolName())))
                    .count();
            verifier.lowCardinality(AgentObservationKeys.Low.VERIFICATION_STATUS,
                    verification != null
                            ? verification.status().name().toLowerCase(java.util.Locale.ROOT)
                            : "unavailable")
                    .lowCardinality(AgentObservationKeys.Low.VERIFY_VERDICT,
                            verificationVerdict(verification))
                    .lowCardinality(AgentObservationKeys.Low.VERIFY_NO_SAVE,
                            noSaveVerdict(request, records))
                    .lowCardinality(AgentObservationKeys.Low.VERIFY_RESULT_COUNT,
                            resultCountVerdict(plan, records))
                    .highCardinality(AgentObservationKeys.High.VERIFY_REQUIRED_STEP_COUNT,
                            requiredSteps)
                    .highCardinality(
                            AgentObservationKeys.High.VERIFY_COMPLETED_REQUIRED_STEP_COUNT,
                            completedRequiredSteps)
                    .highCardinality(AgentObservationKeys.High.VERIFY_EVIDENCE_COUNT,
                            records.size());
        } catch (RuntimeException e) {
            verifier.error(e);
            throw e;
        } finally {
            verifier.stop();
        }
        if (verification == null || verification.deliverable()) {
            return composed;
        }

        AgentTelemetry.AgentObservation recovery = telemetry.start(AgentObservationNames.RECOVERY)
                .highCardinality(AgentObservationKeys.High.TURN_ID, turnId);
        try (Observation.Scope ignored = recovery.openScope()) {
            var action = recoveryPolicy.decide(
                    taskLedger.getPlan(turnId), verification, taskLedger.getRecords(turnId));
            recovery.lowCardinality(AgentObservationKeys.Low.RECOVERY_TYPE,
                    action.type().name().toLowerCase(java.util.Locale.ROOT));
            if (action.type() == com.zzp.aiagent.agent.task.RecoveryActionType.NONE
                    || action.message() == null || action.message().isBlank()) {
                return composed;
            }
            return composed + "\n\n【恢复建议】" + action.message();
        } catch (RuntimeException e) {
            recovery.error(e);
            throw e;
        } finally {
            recovery.stop();
        }
    }

    private AgentTelemetry.AgentObservation turnObservation(
            String chatId, String turnId, ChatRequest request) {
        String message = request.message() != null ? request.message() : "";
        AgentTelemetry.AgentObservation observation = telemetry.start(AgentObservationNames.TURN)
                .highCardinality(AgentObservationKeys.High.CHAT_ID, chatId)
                .highCardinality(AgentObservationKeys.High.TURN_ID, turnId)
                .highCardinality(AgentObservationKeys.High.REQUEST_CONTENT_LENGTH,
                        message.length())
                .highCardinality(AgentObservationKeys.High.REQUEST_CONTENT_HASH,
                        sha256(message))
                .lowCardinality(AgentObservationKeys.Low.REQUEST_CONTENT_CAPTURED, false)
                .lowCardinality(AgentObservationKeys.Low.REQUEST_MODE,
                        request.mode() != null ? request.mode() : ChatRequest.MODE_AUTO);
        String demoCaseId = DemoCaseContext.current();
        if (demoCaseId != null) {
            observation.highCardinality(AgentObservationKeys.High.DEMO_CASE_ID, demoCaseId);
        }
        return observation;
    }

    private static String verificationVerdict(VerificationResult verification) {
        if (verification == null) {
            return "fail";
        }
        return switch (verification.status()) {
            case SUCCESS -> "pass";
            case PARTIAL_SUCCESS -> "partial";
            default -> "fail";
        };
    }

    private static String noSaveVerdict(ChatRequest request, List<ToolExecutionRecord> records) {
        if (!Boolean.FALSE.equals(request.saveGeneratedToGallery())
                && !containsNoSaveRequest(request.message())) {
            return "not_applicable";
        }
        boolean galleryWrite = records.stream().anyMatch(record ->
                record.sideEffect() != null && record.sideEffect().startsWith("GALLERY_"));
        return galleryWrite ? "fail" : "pass";
    }

    private static boolean containsNoSaveRequest(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("不保存") || normalized.contains("不要保存")
                || normalized.contains("do not save") || normalized.contains("don't save");
    }

    private static String resultCountVerdict(TaskPlan plan, List<ToolExecutionRecord> records) {
        if (plan.taskType() != TaskType.WEB_IMAGE_SEARCH) {
            return "not_applicable";
        }
        boolean hasResults = records.stream()
                .filter(ToolExecutionRecord::success)
                .map(record -> record.output().get("candidateCount"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .anyMatch(count -> count.intValue() > 0);
        return hasResults ? "pass" : "fail";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static String newTurnId(String chatId) {
        return chatId + ":" + UUID.randomUUID();
    }

    private static void finishStreamTurn(AgentTelemetry.AgentObservation turn,
                                         SignalType signal, boolean failed) {
        if (signal == SignalType.CANCEL) {
            turn.lowCardinality(AgentObservationKeys.Low.OUTCOME, "cancelled")
                    .event("stream.cancelled");
        } else if (failed || signal == SignalType.ON_ERROR) {
            turn.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error");
        } else {
            turn.lowCardinality(AgentObservationKeys.Low.OUTCOME, "success");
        }
        turn.stop();
    }

    private static ChatResponseVO responseForVerification(String chatId, String text,
                                                          VerificationResult verification) {
        if (verification == null) return ChatResponseVO.textOnly(chatId, text);
        return switch (verification.status()) {
            case PARTIAL_SUCCESS -> ChatResponseVO.partialSuccess(chatId, text);
            case NEED_MORE_INFO -> ChatResponseVO.needMoreInfo(chatId, text);
            default -> ChatResponseVO.textOnly(chatId, text);
        };
    }

    private static Map<String, Object> toolContext(String chatId, String turnId, ChatRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(CurrentImageContext.CHAT_ID_CONTEXT_KEY, chatId);
        context.put(CurrentImageContext.TURN_ID_CONTEXT_KEY, turnId);
        context.put("generationDimensions", resolveGenerationDimensions(request.message()));
        context.put("generationStyle", resolveGenerationStyle(request.message()));
        if (request.saveGeneratedToGallery() != null) {
            context.put("saveGeneratedToGallery", request.saveGeneratedToGallery());
        }
        return context;
    }

    private static String resolveGenerationDimensions(String message) {
        String text = message != null ? message.toLowerCase() : "";
        if (containsAny(text, "竖版", "竖向", "portrait", "9:16")) {
            return "768x1344";
        }
        if (containsAny(text, "横版", "横向", "landscape", "16:9")) {
            return "1344x768";
        }
        return "1024x1024";
    }

    private static String resolveGenerationStyle(String message) {
        String text = message != null ? message.toLowerCase() : "";
        if (containsAny(text, "极简", "minimalist", "minimal")) return "minimalist";
        if (containsAny(text, "写实", "realistic", "photorealistic")) return "realistic";
        if (containsAny(text, "赛博朋克", "霓虹", "cyberpunk", "neon")) return "cyberpunk";
        if (containsAny(text, "插画", "illustration")) return "illustration";
        if (containsAny(text, "二次元", "动漫", "anime")) return "anime";
        if (containsAny(text, "水彩", "watercolor")) return "watercolor";
        if (containsAny(text, "水墨", "ink wash")) return "ink wash";
        if (containsAny(text, "油画", "oil painting")) return "oil painting";
        return "";
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    /**
     * Some models emit textual pseudo tool calls instead of real tool calls.
     * For deterministic, read-only tasks we can safely execute the backend action
     * and write authoritative evidence before verification.
     */
    private String executePlannedFallbackIfNeeded(TaskPlan plan, String turnId, String modelText) {
        if (plan == null || plan.taskType() != TaskType.GALLERY_SEARCH) {
            return modelText;
        }
        if (taskLedger.countSuccess(turnId, "searchGallery") > 0) {
            return modelText;
        }
        String query = extractPseudoGalleryQuery(modelText);
        if (query == null || query.isBlank()) {
            query = plan.userGoal();
        }
        return executeGallerySearchFallback(turnId, query, 5);
    }

    private String executeGallerySearchFallback(String turnId, String query, int limit) {
        String q = query != null ? query.strip() : "";
        Map<String, Object> input = Map.of("query", q, "limit", limit);
        taskLedger.beforeCall(turnId, "searchGallery", input);
        try {
            List<GalleryPicture> results = !q.isBlank() ? galleryService.search(q, limit) : List.of();
            taskLedger.recordSuccess(turnId, "searchGallery", input,
                    Map.of("resultCount", results.size()), ToolExecutionRecord.NONE);
            return formatGallerySearchResult(q, results);
        } catch (Exception e) {
            taskLedger.recordFailure(turnId, "searchGallery", input, e.getMessage());
            return "图库搜索失败：" + e.getMessage();
        }
    }

    private static String extractPseudoGalleryQuery(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = PSEUDO_SEARCH_GALLERY.matcher(text);
        return matcher.matches() ? matcher.group(1).strip() : null;
    }

    private static String formatGallerySearchResult(String query, List<GalleryPicture> results) {
        if (results == null || results.isEmpty()) {
            return "图库中没有找到与「" + query + "」相关的图片。";
        }
        int showCount = Math.min(results.size(), 3);
        StringBuilder sb = new StringBuilder("找到 ").append(results.size())
                .append(" 张相关图片，前 ").append(showCount).append(" 张：\n");
        for (int i = 0; i < showCount; i++) {
            GalleryPicture picture = results.get(i);
            sb.append(i + 1).append(". [ID:").append(picture.id()).append("] ")
                    .append(picture.name() != null ? picture.name() : "未命名");
            if (picture.introduction() != null && !picture.introduction().isBlank()) {
                sb.append(" - ").append(picture.introduction());
            }
            sb.append("\n");
        }
        if (results.size() > showCount) {
            sb.append("其余 ").append(results.size() - showCount).append(" 张编号：");
            List<String> restIds = results.subList(showCount, results.size()).stream()
                    .map(p -> "[ID:" + p.id() + "] " + (p.name() != null ? p.name() : "未命名"))
                    .toList();
            sb.append(String.join("、", restIds));
        }
        return sb.toString().trim();
    }

    // ── 公共工具 ────────────────────────────────────────────────────

    private String buildSelectedReferenceContext(List<Long> referenceIds) {
        if (referenceIds == null || referenceIds.isEmpty()) {
            return "";
        }

        Map<Long, GalleryPicture> pictureMap = new LinkedHashMap<>();
        try {
            for (GalleryPicture picture : galleryService.listByIds(referenceIds)) {
                if (picture != null && picture.id() != null) {
                    pictureMap.put(picture.id(), picture);
                }
            }
        } catch (Exception e) {
            log.warn("[SelectedRef] 批量读取图库参考图失败，降级为逐张读取: {}", e.getMessage());
            for (Long id : referenceIds) {
                try {
                    GalleryPicture picture = galleryService.getById(id);
                    if (picture != null && picture.id() != null) {
                        pictureMap.put(picture.id(), picture);
                    }
                } catch (Exception ignored) {
                    // Missing or inaccessible reference pictures are skipped.
                }
            }
        }

        if (pictureMap.isEmpty()) {
            return "";
        }

        Map<Long, PictureAiProfile> profileMap = new LinkedHashMap<>();
        try {
            List<PictureAiProfile> profiles = pictureAiProfileService.listByPictureIds(referenceIds);
            if (profiles != null) {
                for (PictureAiProfile profile : profiles) {
                    if (profile != null && profile.pictureId() != null) {
                        profileMap.put(profile.pictureId(), profile);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[SelectedRef] 读取参考图画像失败，降级为仅使用图库元数据: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder("【用户从图库中选择了以下参考图片】\n");
        sb.append("这些图片是用户主动选择的参考图。如果用户要求参考上述/这些图片生成类似图片，以下信息视为足够详细；请直接基于参考图的风格、色彩、构图、光影和氛围生成，不要再要求用户补充细节，除非目标完全无法判断或内容不安全。\n");
        int index = 1;
        for (Long id : referenceIds) {
            GalleryPicture picture = pictureMap.get(id);
            if (picture == null) {
                sb.append("参考图").append(index++).append("：[ID:").append(id).append("]（未读取到详情）\n");
                continue;
            }
            sb.append("参考图").append(index++).append("：[ID:").append(picture.id()).append("] ")
                    .append(picture.name() != null ? picture.name() : "未命名").append("\n");
            appendReferenceMetadata(sb, picture);
            appendReferenceProfile(sb, profileMap.get(picture.id()));
        }
        return sb.toString().trim();
    }

    private void appendReferenceMetadata(StringBuilder sb, GalleryPicture picture) {
        appendLine(sb, "  - 简介", picture.introduction());
        appendLine(sb, "  - 分类", picture.category());
        if (picture.tags() != null && !picture.tags().isEmpty()) {
            appendLine(sb, "  - 标签", String.join("、", picture.tags()));
        }
        appendLine(sb, "  - 格式", picture.picFormat());
        if (picture.picWidth() != null && picture.picHeight() != null) {
            appendLine(sb, "  - 尺寸", picture.picWidth() + "x" + picture.picHeight());
        }
        appendLine(sb, "  - 主色", picture.picColor());
        appendLine(sb, "  - 来源", picture.sourceType());
    }

    private void appendReferenceProfile(StringBuilder sb, PictureAiProfile profile) {
        if (profile == null) {
            appendLine(sb, "  - AI画像", "未分析");
            return;
        }
        appendLine(sb, "  - 视觉主体", profile.subject());
        appendLine(sb, "  - 场景", profile.scene());
        appendLine(sb, "  - 风格", profile.style());
        appendLine(sb, "  - 色彩", profile.colors());
        appendLine(sb, "  - 构图", profile.composition());
        appendLine(sb, "  - 光影", profile.lighting());
        appendLine(sb, "  - 氛围", profile.mood());
        appendLine(sb, "  - 画像提示词", profile.imagePrompt());
        appendLine(sb, "  - 检索文本", profile.indexText());
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    /**
     * Auto-save uploaded image to cache gallery so the model can reference it later.
     */
    private GalleryPicture autoSaveToCacheGallery(ChatRequest request) {
        if (!hasImage(request)) return null;
        try {
            String imageBase64 = request.imageBase64();
            if (imageBase64 != null && !imageBase64.isBlank()) {
                GalleryUploadRequest uploadReq = new GalleryUploadRequest(
                        imageBase64,
                        "chat-image-" + System.currentTimeMillis(),
                        null, null, null, null,
                        StorageLocation.CACHE
                );
                GalleryPicture saved = galleryService.upload(uploadReq);
                log.info("[ChatService] 图片自动存入缓存图库 pictureId={} name={}", saved.id(), saved.name());
                return saved;
            }
        } catch (Exception e) {
            log.warn("[ChatService] 自动入库失败，降级为直接发送: {}", e.getMessage());
        }
        return null;
    }

    private boolean hasImage(ChatRequest request) {
        return (request.imageBase64() != null && !request.imageBase64().isBlank())
                || (request.imageUrl() != null && !request.imageUrl().isBlank());
    }

    /**
     * Extract raw base64 image data from the request for the {@code analyzeImage} tool.
     */
    private String extractImageBase64(ChatRequest request) {
        if (request.imageBase64() == null || request.imageBase64().isBlank()) {
            return null;
        }
        String data = request.imageBase64().strip();
        // Strip data URL prefix if present (e.g. "data:image/png;base64,")
        int comma = data.indexOf(',');
        if (data.startsWith("data:image/") && comma >= 0) {
            return data.substring(comma + 1);
        }
        return data;
    }

    /**
     * Human-readable label for tool call progress display.
     */
    private static String toolLabel(String toolName) {
        return switch (toolName) {
            case "searchGallery" -> "正在搜索图库";
            case "getPictureInfo" -> "正在获取图片详情";
            case "analyzeImage" -> "正在分析图片";
            case "generateImage" -> "正在生成图片";
            case "listStyleTemplates" -> "正在查询风格模板";
            case "manageFavorite" -> "正在更新收藏";
            case "webSearch" -> "正在搜索网页";
            case "imageSearch" -> "正在搜索网络图片";
            case "webFetch" -> "正在抓取网页";
            case "downloadImage" -> "正在下载图片";
            case "searchAndDownload" -> "正在搜索并下载图片";
            case "importImage" -> "正在导入图片";
            default -> "正在调用 " + toolName;
        };
    }
}
