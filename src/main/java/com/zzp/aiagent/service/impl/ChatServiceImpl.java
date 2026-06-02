package com.zzp.aiagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.advisor.ContentGuardAdvisor;
import com.zzp.aiagent.advisor.ExceptionGuardAdvisor;
import com.zzp.aiagent.advisor.LoggingAdvisor;
import com.zzp.aiagent.advisor.PromptOptimizeAdvisor;
import com.zzp.aiagent.advisor.RagInjectionAdvisor;
import com.zzp.aiagent.utils.PromptTemplate;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.domain.gallery.GalleryProperties;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.service.ImageGenerationService;
import com.zzp.aiagent.service.VisionAnalysisService;
import com.zzp.aiagent.repository.ChatHistoryRepository;
import com.zzp.aiagent.memory.ChatMemoryProperties;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.service.RagService;
import com.zzp.aiagent.domain.rag.RagContext;
import com.zzp.aiagent.model.dto.image.ImageAgentResponse;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import com.zzp.aiagent.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.content.Media;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

@Service
@Profile("!test")
@Slf4j
public class ChatServiceImpl implements ChatService {

    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("png", "jpeg", "jpg", "webp", "gif");

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final BeanOutputConverter<ImageAgentResponse> outputConverter;
    private final ObjectMapper objectMapper;
    private final ImageGenerationService imageGenerationService;
    private final VisionAnalysisService visionAnalysisService;
    private final RagService ragService;
    private final PromptReferenceAssembler assembler;
    private final GalleryService galleryService;
    private final ChatHistoryRepository chatHistoryRepo;
    private final ChatMemoryProperties chatMemoryProps;
    private final GalleryProperties galleryProps;

    public ChatServiceImpl(ChatModel openAiChatModel, ChatMemory chatMemory, PromptTemplate promptTemplate,
                           ObjectMapper objectMapper, ImageGenerationService imageGenerationService,
                           VisionAnalysisService visionAnalysisService,
                           RagService ragService, PromptReferenceAssembler assembler,
                           GalleryService galleryService, ChatHistoryRepository chatHistoryRepo,
                           ChatMemoryProperties chatMemoryProps, GalleryProperties galleryProps) {
        this.chatMemory = chatMemory;
        this.outputConverter = new BeanOutputConverter<>(ImageAgentResponse.class);
        this.objectMapper = objectMapper;
        this.imageGenerationService = imageGenerationService;
        this.visionAnalysisService = visionAnalysisService;
        this.ragService = ragService;
        this.assembler = assembler;
        this.galleryService = galleryService;
        this.chatHistoryRepo = chatHistoryRepo;
        this.chatMemoryProps = chatMemoryProps;
        this.galleryProps = galleryProps;
        String systemPrompt = promptTemplate.render("default", "system",
                "outputFormat", outputConverter.getFormat());
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        new ContentGuardAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new RagInjectionAdvisor(),
                        new PromptOptimizeAdvisor(promptTemplate),
                        new LoggingAdvisor(),
                        new ExceptionGuardAdvisor()
                )
                .build();
    }

    // ── 非流式入口 ─────────────────────────────────────────────────

    @Override
    public ChatResponseVO chat(ChatRequest request, String chatId) {
        checkConversationLimit(chatId);
        return switch (resolveMode(request)) {
            case ChatRequest.MODE_IMAGE_ANALYSIS -> handleImageAnalysis(request, chatId);
            case ChatRequest.MODE_IMAGE_GENERATION -> handleGeneration(request, chatId);
            case ChatRequest.MODE_CHAT -> handleDiscussion(request, chatId);
            default -> throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的对话模式: " + request.mode());
        };
    }

    // ── 讨论模式 ──────────────────────────────────────────────────

    private ChatResponseVO handleDiscussion(ChatRequest request, String chatId) {
        GalleryPicture saved = autoSaveToCacheGallery(request);
        ImageAgentResponse aiResp = chatClient.prompt()
                .user(buildUserSpec(request, saved))
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 10)
                        .param("chatId", chatId))
                .call()
                .entity(outputConverter);
        return ChatResponseVO.objToVo(aiResp, chatId);
    }

    // ── 图片分析模式 ────────────────────────────────────────────────

    private ChatResponseVO handleImageAnalysis(ChatRequest request, String chatId) {
        validateImageAnalysisRequest(request);
        autoSaveToCacheGallery(request); // 自动入库
        VisionAnalysisResult result = visionAnalysisService.analyze(
                request.message(), request.imageBase64(), request.imageUrl());
        ChatResponseVO data = ChatResponseVO.imageAnalyzed(chatId, result);
        saveImageAnalysisMemory(request, chatId, result);
        return data;
    }

    // ── RAG 共同逻辑 ──────────────────────────────────────────────

    private record RagPrepared(String originalMsg, String augmentedMsg, Object debugData) {
    }

    private RagPrepared prepareRagContext(ChatRequest request, String chatId, String logTag) {
        String msg = request.message() != null && !request.message().isBlank()
                ? request.message()
                : "基于以上对话内容，请生成最终的图片生成参数";

        // P3: 图→图检索 — 当有图片但无文本消息时，用视觉分析结果作为 RAG 检索 query
        ChatRequest ragRequest = request;
        boolean useImageForSearch = hasImage(request)
                && (request.message() == null || request.message().isBlank());
        if (useImageForSearch) {
            try {
                VisionAnalysisResult vision = visionAnalysisService.analyze(
                        "请提取可用于图库检索的视觉特征（主体、风格、色彩、构图）",
                        request.imageBase64(), request.imageUrl());
                String visionQuery = buildImageSearchQuery(vision);
                log.info("[{}] 从参考图片提取检索词 chatId={}: {}", logTag, chatId, visionQuery);
                ragRequest = requestWithMessage(request, visionQuery);
                // 同时更新 msg 用于生图 Prompt
                msg = "基于上传的参考图片风格，请生成最终的图片生成参数";
            } catch (Exception e) {
                log.warn("[{}] 图片视觉分析失败，使用默认检索 chatId={}: {}", logTag, chatId, e.getMessage());
            }
        }

        RagContext ragCtx = ragService.buildContext(ragRequest);
        log.info("[{}] 上下文构建完成 chatId={}:\n{}", logTag, chatId, assembler.buildDebugInfo(ragCtx));
        String augmentedMsg = assembler.assemble(msg, ragCtx);
        return new RagPrepared(msg, augmentedMsg, assembler.buildDebugData(ragCtx, augmentedMsg));
    }

    // ── 图→图检索辅助 ──────────────────────────────────────────────

    /**
     * P3: 从视觉分析结果中提取图库检索用文本。
     * 拼接主体、风格、色彩、构图等可检索属性。
     */
    private String buildImageSearchQuery(VisionAnalysisResult vision) {
        StringBuilder sb = new StringBuilder();
        appendIfNotEmpty(sb, vision.subject());
        appendIfNotEmpty(sb, vision.style());
        appendIfNotEmpty(sb, vision.colors());
        appendIfNotEmpty(sb, vision.composition());
        return !sb.isEmpty() ? sb.toString() : "分析图片";
    }

    private static void appendIfNotEmpty(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(value);
        }
    }

    /**
     * P3: 创建替换了 message 字段的 ChatRequest 副本，用于 RAG 检索。
     */
    private ChatRequest requestWithMessage(ChatRequest src, String newMessage) {
        return new ChatRequest(
                newMessage,
                src.chatId(),
                src.generationMode(),
                src.imageBase64(),
                src.imageUrl(),
                src.mode(),
                src.referencePictureIds(),
                src.useGalleryRag(),
                src.referenceMode(),
                src.styleTemplateCode(),
                src.saveGeneratedToGallery()
        );
    }

    // ── 生成模式 ──────────────────────────────────────────────────

    private ChatResponseVO handleGeneration(ChatRequest request, String chatId) {
        // 1.自动入库
        GalleryPicture saved = autoSaveToCacheGallery(request);
        // 2.RAG上下文装配
        RagPrepared rag = prepareRagContext(request, chatId, "RAG");
        ImageAgentResponse aiResp = chatClient.prompt()
                .user(spec -> buildGenerationUserSpec(spec, rag.originalMsg, saved))
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 50)
                        .param("chatId", chatId)
                        .param(RagInjectionAdvisor.KEY_RAG_AUGMENTED, rag.augmentedMsg))
                .call()
                .entity(outputConverter);

        ImageGenerationResult genResult = imageGenerationService.generate(
                aiResp.imagePrompt(), aiResp.style(), aiResp.dimensions());

        if (Boolean.TRUE.equals(request.saveGeneratedToGallery())) {
            saveToGallery(aiResp, genResult, chatId);
        }

        return ChatResponseVO.imageGenerated(chatId, genResult.imageUrl(), genResult.imageBase64(),
                aiResp.message(), aiResp.imagePrompt(), aiResp.style(), aiResp.dimensions(), aiResp.revisedPrompt(),
                rag.debugData);
    }

    // ── 流式入口 ──────────────────────────────────────────────────

    @Override
    public Flux<StreamEventVO> chatStream(ChatRequest request, String chatId) {
        checkConversationLimit(chatId);
        return switch (resolveMode(request)) {
            case ChatRequest.MODE_IMAGE_ANALYSIS -> handleImageAnalysisStream(request, chatId);
            case ChatRequest.MODE_IMAGE_GENERATION -> handleGenerationStream(request, chatId);
            case ChatRequest.MODE_CHAT -> handleDiscussionStream(request, chatId);
            default -> Flux.just(StreamEventVO.error("不支持的对话模式: " + request.mode()));
        };
    }

    // ── 流式讨论模式 ──────────────────────────────────────────────

    private Flux<StreamEventVO> handleDiscussionStream(ChatRequest request, String chatId) {
        GalleryPicture saved = autoSaveToCacheGallery(request);
        StringBuilder accumulator = new StringBuilder();

        Flux<StreamEventVO> tokenFlux = chatClient.prompt()
                .user(buildUserSpec(request, saved))
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 10)
                        .param("chatId", chatId))
                .stream()
                .content()
                .doOnNext(accumulator::append)
                .map(StreamEventVO::token);

        Flux<StreamEventVO> doneEvent = Flux.defer(() -> {
            String fullText = accumulator.toString();
            ChatResponseVO data = parseStructuredOrFallback(fullText, chatId);
            return Flux.just(StreamEventVO.done(chatId, data));
        });

        return Flux.concat(
                Flux.just(StreamEventVO.chatId(chatId)),
                tokenFlux,
                doneEvent
        ).onErrorResume(e -> {
            String errorMsg;
            if (e instanceof BusinessException be) {
                log.warn("[Stream] 业务异常 chatId={} code={}", chatId, be.getCode());
                errorMsg = be.getMessage();
            } else {
                log.error("[Stream] 未知异常 chatId={}", chatId, e);
                errorMsg = "流式处理异常，请重试";
            }
            return Flux.just(StreamEventVO.error(errorMsg));
        });
    }

    // ── 流式图片分析模式 ────────────────────────────────────────────

    private Flux<StreamEventVO> handleImageAnalysisStream(ChatRequest request, String chatId) {
        Flux<StreamEventVO> doneEvent = Flux.defer(() -> {
            validateImageAnalysisRequest(request);
            autoSaveToCacheGallery(request); // 自动入库
            VisionAnalysisResult result = visionAnalysisService.analyze(
                    request.message(), request.imageBase64(), request.imageUrl());
            ChatResponseVO data = ChatResponseVO.imageAnalyzed(chatId, result);
            saveImageAnalysisMemory(request, chatId, result);
            return Flux.just(StreamEventVO.done(chatId, data));
        });

        return Flux.concat(
                Flux.just(
                        StreamEventVO.chatId(chatId),
                        StreamEventVO.progress(chatId, "正在分析图片...")),
                doneEvent
        ).onErrorResume(e -> {
            String errorMsg;
            if (e instanceof BusinessException be) {
                log.warn("[Stream-ImageAnalysis] 业务异常 chatId={} code={}", chatId, be.getCode());
                errorMsg = be.getMessage();
            } else {
                log.error("[Stream-ImageAnalysis] 未知异常 chatId={}", chatId, e);
                errorMsg = "图片分析异常，请重试";
            }
            return Flux.just(StreamEventVO.error(errorMsg));
        });
    }

    // ── 流式生成模式 ──────────────────────────────────────────────

    private Flux<StreamEventVO> handleGenerationStream(ChatRequest request, String chatId) {
        // 自动存储发送过来的图片
        GalleryPicture saved = autoSaveToCacheGallery(request);
        //
        RagPrepared rag = prepareRagContext(request, chatId, "RAG-Stream");

        Flux<StreamEventVO> generateEvent = Flux.defer(() -> {
            ImageAgentResponse aiResp = chatClient.prompt()
                    .user(spec -> buildGenerationUserSpec(spec, rag.originalMsg, saved))
                    .advisors(spec -> spec
                            .param(ChatMemory.CONVERSATION_ID, chatId)
                            .param("chat_memory_retrieve_size", 50)
                            .param("chatId", chatId))
                    .call()
                    .entity(outputConverter);

            return Flux.concat(
                    Flux.just(StreamEventVO.progress(chatId, "正在生成图片...")),
                    Flux.defer(() -> {
                        ImageGenerationResult genResult = imageGenerationService.generate(
                                aiResp.imagePrompt(), aiResp.style(), aiResp.dimensions());
                        if (Boolean.TRUE.equals(request.saveGeneratedToGallery())) {
                            try {
                                saveToGallery(aiResp, genResult, chatId);
                            } catch (Exception e) {
                                log.warn("[RAG-Stream] 保存到图库失败 chatId={}", chatId, e);
                            }
                        }
                        ChatResponseVO data = ChatResponseVO.imageGenerated(chatId,
                                genResult.imageUrl(), genResult.imageBase64(), aiResp.message(),
                                aiResp.imagePrompt(), aiResp.style(), aiResp.dimensions(), aiResp.revisedPrompt(),
                                rag.debugData);
                        return Flux.just(StreamEventVO.done(chatId, data));
                    })
            );
        });

        return Flux.concat(
                Flux.just(
                        StreamEventVO.chatId(chatId),
                        StreamEventVO.progress(chatId, "正在整理图片 Prompt...")),
                generateEvent
        ).onErrorResume(e -> {
            String errorMsg;
            if (e instanceof BusinessException be) {
                log.warn("[Stream-Generation] 业务异常 chatId={} code={}", chatId, be.getCode());
                errorMsg = be.getMessage();
            } else {
                log.error("[Stream-Generation] 未知异常 chatId={}", chatId, e);
                errorMsg = "流式处理异常，请重试";
            }
            return Flux.just(StreamEventVO.error(errorMsg));
        });
    }

    // ── 公共工具 ──────────────────────────────────────────────────

    private String resolveMode(ChatRequest request) {
        String mode = request.mode();
        if (mode != null && !mode.isBlank()) {
            return mode.trim().toLowerCase(Locale.ROOT);
        }
        return Boolean.TRUE.equals(request.generationMode())
                ? ChatRequest.MODE_IMAGE_GENERATION
                : ChatRequest.MODE_CHAT;
    }

    private void validateImageAnalysisRequest(ChatRequest request) {
        ThrowUtils.throwIf(!hasImage(request), ErrorCode.PARAMS_ERROR, "请先上传需要分析的图片");
        if (request.imageBase64() == null || request.imageBase64().isBlank()) {
            return;
        }
        String data = request.imageBase64().trim();
        String lower = data.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) {
            int slash = lower.indexOf('/');
            int semicolon = lower.indexOf(';');
            ThrowUtils.throwIf(!lower.startsWith("data:image/") || slash < 0 || semicolon < slash,
                    ErrorCode.IMAGE_FORMAT_INVALID, "不支持的图片格式");
            String type = lower.substring(slash + 1, semicolon);
            ThrowUtils.throwIf(!ALLOWED_IMAGE_TYPES.contains(type), ErrorCode.IMAGE_FORMAT_INVALID,
                    "不支持的图片格式: " + type);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stripDataUrlPrefix(data));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.IMAGE_FORMAT_INVALID, "图片数据不是有效的 Base64");
        }
        ThrowUtils.throwIf(bytes.length > MAX_IMAGE_BYTES, ErrorCode.IMAGE_TOO_LARGE,
                "图片大小 " + (bytes.length / 1024 / 1024) + "MB，最大允许 10MB");
    }

    private boolean hasImage(ChatRequest request) {
        return (request.imageBase64() != null && !request.imageBase64().isBlank())
                || (request.imageUrl() != null && !request.imageUrl().isBlank());
    }

    private void saveImageAnalysisMemory(ChatRequest request, String chatId, VisionAnalysisResult result) {
        String userText = request.message() == null || request.message().isBlank()
                ? "请分析这张图片"
                : request.message();
        chatMemory.add(chatId, List.of(
                new UserMessage(userText),
                new AssistantMessage(result.memoryText())
        ));
    }

    private Consumer<ChatClient.PromptUserSpec> buildUserSpec(ChatRequest request, GalleryPicture savedPicture) {
        return spec -> {
            spec.text(request.message());
            // 已入库 → 用图库 URL（ChatMemory 可识别为 GALLERY）
            if (savedPicture != null && savedPicture.url() != null) {
                try {
                    String mime = mimeTypeFromFormat(savedPicture.picFormat());
                    spec.media(new Media(MimeTypeUtils.parseMimeType(mime),
                            new URL(savedPicture.url()).toURI()));
                    return;
                } catch (Exception e) {
                    log.warn("[ChatService] 图库URL构造失败，降级发送: {}", e.getMessage());
                }
            }
            // 未入库 → 直接发送 base64 bytes
            String imageBase64 = request.imageBase64();
            String imageUrl = request.imageUrl();
            if (imageBase64 != null && !imageBase64.isBlank()) {
                byte[] bytes = Base64.getDecoder().decode(stripDataUrlPrefix(imageBase64));
                spec.media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(bytes)));
            } else if (imageUrl != null && !imageUrl.isBlank()) {
                try {
                    spec.media(new Media(MimeTypeUtils.IMAGE_PNG, new URL(imageUrl).toURI()));
                } catch (Exception e) {
                    log.warn("[ChatService] 无效图片URL: {}", imageUrl, e);
                }
            }
        };
    }

    private void buildGenerationUserSpec(ChatClient.PromptUserSpec spec, String augmentedMsg, GalleryPicture savedPicture) {
        spec.text(augmentedMsg);
        if (savedPicture != null && savedPicture.url() != null) {
            try {
                String mime = mimeTypeFromFormat(savedPicture.picFormat());
                spec.media(new Media(MimeTypeUtils.parseMimeType(mime),
                        new URL(savedPicture.url()).toURI()));
            } catch (Exception e) {
                log.warn("[ChatService] 生成模式：图库URL构造失败: {}", e.getMessage());
            }
        }
    }

    private static String mimeTypeFromFormat(String picFormat) {
        if (picFormat == null) return "image/png";
        return switch (picFormat.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    private String stripDataUrlPrefix(String imageBase64) {
        String trimmed = imageBase64.trim();
        int comma = trimmed.indexOf(',');
        if (trimmed.startsWith("data:image/") && comma >= 0) {
            return trimmed.substring(comma + 1);
        }
        return trimmed;
    }

    private ChatResponseVO parseStructuredOrFallback(String rawText, String chatId) {
        try {
            ImageAgentResponse parsed = objectMapper.readValue(rawText, ImageAgentResponse.class);
            return ChatResponseVO.objToVo(parsed, chatId);
        } catch (Exception e) {
            log.warn("[Stream] 无法解析LLM输出为ImageAgentResponse，回退为纯文本 chatId={}", chatId);
            return ChatResponseVO.textOnly(chatId, rawText);
        }
    }

    private void checkConversationLimit(String chatId) {
        int count = chatHistoryRepo.countByConversation(chatId);
        ThrowUtils.throwIf(count >= chatMemoryProps.maxConversationMessages(),
                ErrorCode.PARAMS_ERROR,
                "会话消息已达上限(" + count + "/" + chatMemoryProps.maxConversationMessages() + ")，请开启新会话");
    }

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
            // URL 类型图片暂不做自动入库（需下载后再上传）
        } catch (Exception e) {
            log.warn("[ChatService] 自动入库失败，降级为直接发送: {}", e.getMessage());
        }
        return null;
    }

    private void saveToGallery(ImageAgentResponse aiResp, ImageGenerationResult genResult, String chatId) {
        try {
            String name = aiResp.imagePrompt() != null ? aiResp.imagePrompt() : "AI生成图片";
            String introduction = aiResp.revisedPrompt();
            String category = "ai-generated";
            List<String> tags = aiResp.style() != null && !aiResp.style().isBlank()
                    ? List.of(aiResp.style())
                    : List.of();

            if (genResult.imageBase64() != null && !genResult.imageBase64().isBlank()) {
                GalleryUploadRequest uploadReq = new GalleryUploadRequest(
                        genResult.imageBase64(), name, introduction, category, tags, null,
                        StorageLocation.MAIN);
                GalleryPicture saved = galleryService.upload(uploadReq);
                log.info("[RAG] 生成图片已保存到图库 chatId={} pictureId={}", chatId, saved.id());
            } else if (genResult.imageUrl() != null && !genResult.imageUrl().isBlank()) {
                GalleryImportUrlRequest importReq = new GalleryImportUrlRequest(
                        genResult.imageUrl(), name, introduction, category, tags);
                GalleryPicture saved = galleryService.importUrl(importReq);
                log.info("[RAG] 生成图片已通过URL导入图库 chatId={} pictureId={}", chatId, saved.id());
            } else {
                log.warn("[RAG] 生成的图片既无Base64也无URL，无法保存到图库 chatId={}", chatId);
            }
        } catch (Exception e) {
            log.warn("[RAG] 保存生成图片到图库失败 chatId={}，继续主流程", chatId, e);
        }
    }
}
