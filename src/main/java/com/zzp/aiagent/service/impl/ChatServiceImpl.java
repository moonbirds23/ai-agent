package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.agent.Agent;
import com.zzp.aiagent.agent.AgentConfig;
import com.zzp.aiagent.agent.task.RecoveryPolicy;
import com.zzp.aiagent.agent.task.ResponseComposer;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskPlanner;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.agent.task.VerificationResult;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
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
import com.zzp.aiagent.service.ChatMediaService;
import com.zzp.aiagent.service.ChatService;
import com.zzp.aiagent.service.ConversationLimitService;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.tool.CurrentImageContext;
import com.zzp.aiagent.tool.GalleryAgentTools;
import com.zzp.aiagent.tool.PexelsSearchTools;
import com.zzp.aiagent.tool.ToolProgressContext;
import com.zzp.aiagent.tool.WebSearchTools;
import com.zzp.aiagent.utils.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat service — orchestration role.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Pre-processing: image auto-save, media construction, reference context.</li>
 *   <li>Delegate execution to {@link Agent} (which owns the {@code ChatClient}).</li>
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

    private final Agent agent;
    private final GalleryService galleryService;
    private final PictureAiProfileService pictureAiProfileService;
    private final ChatMediaService chatMediaService;
    private final ConversationLimitService conversationLimitService;
    private final CurrentImageContext currentImageContext;
    private final ToolProgressContext toolProgressContext;
    private final TaskLedger taskLedger;
    private final TaskPlanner taskPlanner;
    private final RecoveryPolicy recoveryPolicy;

    public ChatServiceImpl(ChatModel openAiChatModel, ChatMemory chatMemory, PromptTemplate promptTemplate,
                           GalleryAgentTools galleryAgentTools,
                           WebSearchTools webSearchTools,
                           PexelsSearchTools pexelsSearchTools,
                           GalleryService galleryService,
                           PictureAiProfileService pictureAiProfileService,
                           ChatMediaService chatMediaService,
                           ConversationLimitService conversationLimitService,
                           CurrentImageContext currentImageContext,
                           ToolProgressContext toolProgressContext,
                           TaskLedger taskLedger,
                           TaskPlanner taskPlanner,
                           RecoveryPolicy recoveryPolicy) {
        this.galleryService = galleryService;
        this.pictureAiProfileService = pictureAiProfileService;
        this.chatMediaService = chatMediaService;
        this.conversationLimitService = conversationLimitService;
        this.currentImageContext = currentImageContext;
        this.toolProgressContext = toolProgressContext;
        this.taskLedger = taskLedger;
        this.taskPlanner = taskPlanner;
        this.recoveryPolicy = recoveryPolicy;

        this.agent = new Agent("cloud-gallery-agent",
                AgentConfig.of("cloud-gallery-agent"),
                openAiChatModel,
                chatMemory,
                promptTemplate,
                galleryAgentTools,
                webSearchTools,
                pexelsSearchTools);
    }

    // ── 非流式入口 ──────────────────────────────────────────────────

    @Override
    public ChatResponseVO chat(ChatRequest request, String chatId) {
        conversationLimitService.checkLimit(chatId);
        GalleryPicture saved = autoSaveToCacheGallery(request);
        String turnId = newTurnId(chatId);
        TaskPlan plan = taskPlanner.plan(request, turnId);
        taskLedger.startPlan(plan);
        currentImageContext.bind(turnId, extractImageBase64(request));

        try {
            String userText = buildUserText(request);
            Media media = chatMediaService.createMedia(saved, request.imageBase64(), request.imageUrl());
            Map<String, Object> ctx = toolContext(chatId, turnId, request);

            String response = agent.run(userText, media, ctx, chatId).content();

            ToolProgressContext.ToolTraceSnapshot trace = toolProgressContext.snapshot(turnId);
            if (trace.imageGenerated()) {
                ImageGeneratedEventVO imageData = toolProgressContext.getGeneratedImage(turnId);
                if (imageData != null) {
                    log.info("[Chat] 非流式返回生图结果 chatId={}", chatId);
                    return ChatResponseVO.imageGenerated(chatId, imageData.imageUrl(),
                            imageData.imageBase64(),
                            response != null && !response.isBlank() ? response : "图片已生成");
                }
            }

            String effectiveResponse = executePlannedFallbackIfNeeded(plan, turnId, response);
            String safeResponse = ResponseComposer.composeVerified(effectiveResponse, taskLedger, turnId, recoveryPolicy);
            return ChatResponseVO.textOnly(chatId, safeResponse);
        } finally {
            toolProgressContext.clear(turnId);
            taskLedger.clear(turnId);
            currentImageContext.clear(turnId);
        }
    }

    // ── 流式入口 ────────────────────────────────────────────────────

    @Override
    public Flux<StreamEventVO> chatStream(ChatRequest request, String chatId) {
        conversationLimitService.checkLimit(chatId);
        GalleryPicture saved = autoSaveToCacheGallery(request);
        String turnId = newTurnId(chatId);
        TaskPlan plan = taskPlanner.plan(request, turnId);
        taskLedger.startPlan(plan);
        currentImageContext.bind(turnId, extractImageBase64(request));

        String userText = buildUserText(request);
        Media media = chatMediaService.createMedia(saved, request.imageBase64(), request.imageUrl());
        Map<String, Object> ctx = toolContext(chatId, turnId, request);

        StringBuilder accumulator = new StringBuilder();
        Sinks.Many<StreamEventVO> progressSink = Sinks.many().multicast().onBackpressureBuffer();
        toolProgressContext.bind(turnId, chatId, progressSink);

        Flux<StreamEventVO> eventFlux = agent.streamRaw(userText, media, ctx, chatId)
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
                                log.info("[Stream] 工具调用 chatId={} tool={} args={}", chatId, tc.name(), tc.arguments());
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
            String safeText = ResponseComposer.composeVerified(effectiveText, taskLedger, turnId, recoveryPolicy);
            return Flux.just(
                    StreamEventVO.taskVerified(chatId, taskLedger.snapshot(turnId)),
                    StreamEventVO.done(chatId, ChatResponseVO.textOnly(chatId, safeText)));
        });

        Flux<StreamEventVO> mergedEvents = Flux.merge(
                progressSink.asFlux(),
                eventFlux.doFinally(signal -> toolProgressContext.complete(turnId))
        );

        return Flux.concat(
                Flux.just(StreamEventVO.chatId(chatId)),
                Flux.just(StreamEventVO.taskPlanned(chatId, taskLedger.snapshot(turnId))),
                mergedEvents,
                doneEvent
        ).onErrorResume(e -> {
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
                    StreamEventVO.taskVerified(chatId, taskLedger.snapshot(turnId)),
                    StreamEventVO.error(errorMsg));
        }).doFinally(signal -> {
            toolProgressContext.clear(turnId);
            taskLedger.clear(turnId);
            currentImageContext.clear(turnId);
        });
    }

    private static Map<String, Object> toolContext(String chatId, String turnId, ChatRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(CurrentImageContext.CHAT_ID_CONTEXT_KEY, chatId);
        context.put(CurrentImageContext.TURN_ID_CONTEXT_KEY, turnId);
        if (request.saveGeneratedToGallery() != null) {
            context.put("saveGeneratedToGallery", request.saveGeneratedToGallery());
        }
        return context;
    }

    private static String newTurnId(String chatId) {
        return chatId + ":" + UUID.randomUUID();
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

    /**
     * Build the full user text including selected-reference context.
     */
    private String buildUserText(ChatRequest request) {
        String text = request.message() != null ? request.message() : "";
        String referenceContext = buildSelectedReferenceContext(request.referencePictureIds());
        if (!referenceContext.isBlank()) {
            text = referenceContext + "\n【用户原始需求】\n" + text;
        }
        return text;
    }

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
