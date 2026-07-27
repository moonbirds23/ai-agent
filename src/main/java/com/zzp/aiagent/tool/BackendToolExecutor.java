package com.zzp.aiagent.tool;

import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.domain.pexels.PexelsPhoto;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import com.zzp.aiagent.domain.pexels.PexelsSearchRequest;
import com.zzp.aiagent.domain.pexels.PexelsSearchResult;
import com.zzp.aiagent.domain.template.StyleTemplate;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.vo.ImageGeneratedEventVO;
import com.zzp.aiagent.model.vo.ImageCandidateVO;
import com.zzp.aiagent.model.vo.ImageCandidatesEventVO;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.ImageGenerationService;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.service.VisionAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Profile("!test")
public class BackendToolExecutor implements ToolExecutor {

    private final GalleryService galleryService;
    private final ImageGenerationService imageGenerationService;
    private final VisionAnalysisService visionAnalysisService;
    private final StyleTemplateService styleTemplateService;
    private final PexelsPhotoService pexelsPhotoService;
    private final ToolProgressContext progressContext;
    private final ChatClient promptClient;

    public BackendToolExecutor(GalleryService galleryService,
                               ImageGenerationService imageGenerationService,
                               VisionAnalysisService visionAnalysisService,
                               StyleTemplateService styleTemplateService,
                               PexelsPhotoService pexelsPhotoService,
                               ToolProgressContext progressContext,
                               ChatModel chatModel) {
        this.galleryService = galleryService;
        this.imageGenerationService = imageGenerationService;
        this.visionAnalysisService = visionAnalysisService;
        this.styleTemplateService = styleTemplateService;
        this.pexelsPhotoService = pexelsPhotoService;
        this.progressContext = progressContext;
        this.promptClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        Convert the supplied image request and reference summary into one concise English
                        image-generation prompt. Include subject, style, colors, composition, lighting and mood.
                        Return only the prompt, without JSON, markdown or explanation.
                        """)
                .build();
    }

    @Override
    public ToolExecutionRecord execute(String turnId, TaskStep step, ToolExecutionContext context) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> input = step.input() != null ? step.input() : Map.of();
        try {
            ToolExecutionRecord record = switch (step.toolName()) {
                case "searchGallery" -> searchGallery(turnId, input, context);
                case "getPictureInfo" -> getPictureInfo(turnId, input, context);
                case "analyzeImage" -> analyzeImage(turnId, input, context);
                case "generateImage" -> generateImage(turnId, input, context);
                case "listStyleTemplates" -> listStyles(turnId, input);
                case "pexelsSearchPhotos" -> searchPexels(turnId, input, context);
                case "pexelsCuratedPhotos" -> curatedPexels(turnId, input);
                default -> ToolExecutionRecord.failure(turnId, step.toolName(), input,
                        "UNSUPPORTED_TOOL", "Manual executor does not support tool: " + step.toolName(),
                        false, "Use Spring AI auto executor for this task");
            };
            return record.withTiming(startedAt, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("[BackendToolExecutor] tool failed turnId={} tool={}", turnId, step.toolName(), e);
            return ToolExecutionRecord.failure(turnId, step.toolName(), input, e.getMessage())
                    .withTiming(startedAt, System.currentTimeMillis());
        }
    }

    private ToolExecutionRecord searchGallery(String turnId, Map<String, Object> input,
                                               ToolExecutionContext context) {
        String query = stringValue(input.get("query"));
        if (query.isBlank()) query = context.input().userText();
        int limit = intValue(input.get("limit"), 5);
        List<GalleryPicture> pictures = query != null && !query.isBlank()
                ? galleryService.search(query, limit) : List.of();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultCount", pictures.size());
        output.put("pictureIds", pictures.stream().map(GalleryPicture::id).toList());
        output.put("references", pictures.stream().map(BackendToolExecutor::pictureSummary).toList());
        return ToolExecutionRecord.success(turnId, "searchGallery",
                Map.of("query", query != null ? query : "", "limit", limit),
                output, ToolExecutionRecord.NONE);
    }

    private ToolExecutionRecord getPictureInfo(String turnId, Map<String, Object> input,
                                               ToolExecutionContext context) {
        Object rawId = input.get("pictureId");
        if (!(rawId instanceof Number)) {
            Object slotValue = context.input().plan().slots().get("referencePictureIds");
            List<Long> referenceIds = slotValue instanceof List<?> ids
                    ? ids.stream().filter(Number.class::isInstance).map(Number.class::cast)
                    .map(Number::longValue).toList()
                    : List.of();
            if (referenceIds.isEmpty()) {
                return ToolExecutionRecord.failure(turnId, "getPictureInfo", input,
                        "Missing pictureId");
            }
            rawId = referenceIds.getFirst();
        }
        long pictureId = ((Number) rawId).longValue();
        GalleryPicture picture = galleryService.getById(pictureId);
        if (picture == null) {
            return ToolExecutionRecord.failure(turnId, "getPictureInfo", input,
                    "Picture not found: " + pictureId);
        }
        return ToolExecutionRecord.success(turnId, "getPictureInfo", Map.of("pictureId", pictureId),
                Map.of("pictureId", pictureId, "summary", pictureSummary(picture)),
                ToolExecutionRecord.NONE);
    }

    private ToolExecutionRecord analyzeImage(String turnId, Map<String, Object> input,
                                             ToolExecutionContext context) {
        Object rawImage = context.input().toolContext().get("imageBase64");
        String imageBase64 = rawImage instanceof String value ? value : "";
        if (imageBase64.isBlank()) {
            return ToolExecutionRecord.failure(turnId, "analyzeImage", input,
                    "Current turn has no image to analyze");
        }
        String instruction = stringValue(input.get("instruction"));
        VisionAnalysisResult result = visionAnalysisService.analyze(
                instruction.isBlank() ? context.input().userText() : instruction,
                imageBase64, null);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("subject", safe(result.subject()));
        output.put("style", safe(result.style()));
        output.put("colors", safe(result.colors()));
        output.put("composition", safe(result.composition()));
        output.put("imagePrompt", safe(result.imagePrompt()));
        return ToolExecutionRecord.success(turnId, "analyzeImage", input, output,
                ToolExecutionRecord.NONE);
    }

    private ToolExecutionRecord generateImage(String turnId, Map<String, Object> input,
                                              ToolExecutionContext context) {
        String prompt = buildGenerationPrompt(input, context);
        String style = resolveStyle(input, context.input().userText());
        String dimensions = resolveDimensions(input, context.input().userText());
        ImageGenerationResult result = imageGenerationService.generate(
                prompt, style.isBlank() ? null : style, dimensions.isBlank() ? null : dimensions);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("imageUrl", safe(result.imageUrl()));
        output.put("imageBase64", safe(result.imageBase64()));
        output.put("revisedPrompt", safe(result.revisedPrompt()));
        progressContext.recordGeneratedImage(turnId, new ImageGeneratedEventVO(
                result.imageUrl(), result.imageBase64(), prompt, result.revisedPrompt(),
                style, dimensions, result.metadata()));
        return ToolExecutionRecord.success(turnId, "generateImage",
                Map.of("prompt", prompt, "style", style, "dimensions", dimensions),
                output, ToolExecutionRecord.IMAGE_GENERATED);
    }

    private ToolExecutionRecord searchPexels(String turnId, Map<String, Object> input,
                                             ToolExecutionContext context) {
        String query = stringValue(input.get("query"));
        if (query.isBlank()) query = context.input().userText();
        int limit = Math.clamp(intValue(input.get("limit"),
                intValue(input.get("perPage"), 5)), 1, 10);
        String orientation = stringValue(input.get("orientation"));
        String size = stringValue(input.get("size"));
        String color = stringValue(input.get("color"));
        PexelsSearchResult result = pexelsPhotoService.search(new PexelsSearchRequest(
                query, limit, 1, blankToNull(orientation), blankToNull(size),
                blankToNull(color), "zh-CN"));
        return pexelsResult(turnId, "pexelsSearchPhotos", query, input, result);
    }

    private ToolExecutionRecord curatedPexels(String turnId, Map<String, Object> input) {
        int limit = Math.clamp(intValue(input.get("limit"),
                intValue(input.get("perPage"), 5)), 1, 10);
        PexelsSearchResult result = pexelsPhotoService.curated(limit, 1);
        return pexelsResult(turnId, "pexelsCuratedPhotos", "curated", input, result);
    }

    private ToolExecutionRecord pexelsResult(String turnId, String toolName, String query,
                                             Map<String, Object> input,
                                             PexelsSearchResult result) {
        List<PexelsPhoto> photos = result.photos() != null ? result.photos() : List.of();
        List<ImageCandidateVO> candidates = photos.stream()
                .map(BackendToolExecutor::toCandidate)
                .toList();
        progressContext.recordImageCandidates(turnId,
                new ImageCandidatesEventVO(query, "pexels", candidates));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("candidateCount", photos.size());
        output.put("totalResults", result.totalResults());
        output.put("candidates", candidates);
        return ToolExecutionRecord.success(turnId, toolName, input, output,
                photos.isEmpty() ? ToolExecutionRecord.NONE
                        : ToolExecutionRecord.IMAGE_CANDIDATES_RETURNED);
    }

    private ToolExecutionRecord listStyles(String turnId, Map<String, Object> input) {
        List<StyleTemplate> templates = styleTemplateService.listAll();
        return ToolExecutionRecord.success(turnId, "listStyleTemplates", input,
                Map.of("templateCount", templates.size(),
                        "templates", templates.stream().map(StyleTemplate::code).toList()),
                ToolExecutionRecord.NONE);
    }

    private String buildGenerationPrompt(Map<String, Object> input, ToolExecutionContext context) {
        String requested = stringValue(input.get("prompt"));
        if (!requested.isBlank() && requested.chars().allMatch(c -> c < 128)) {
            return requested;
        }
        StringBuilder source = new StringBuilder(context.input().userText());
        if (context.input().executionContext() != null && !context.input().executionContext().isBlank()) {
            source.append("\nReference context:\n").append(context.input().executionContext());
        }
        for (ToolExecutionRecord record : context.completedRecords()) {
            if ("searchGallery".equals(record.toolName()) && record.success()) {
                source.append("\nGallery references:\n").append(record.output().get("references"));
            }
            if ("analyzeImage".equals(record.toolName()) && record.success()) {
                source.append("\nVisual analysis:\n").append(record.output());
            }
        }
        String generated = promptClient.prompt().user(source.toString()).call().content();
        return generated != null && !generated.isBlank() ? generated.strip() : context.input().userText();
    }

    private static String pictureSummary(GalleryPicture picture) {
        return "[ID:" + picture.id() + "] " + safe(picture.name()) + " "
                + safe(picture.introduction()) + " tags=" + picture.tags();
    }

    private static ImageCandidateVO toCandidate(PexelsPhoto photo) {
        String displayUrl = photo.src().medium() != null && !photo.src().medium().isBlank()
                ? photo.src().medium() : photo.src().large();
        String title = photo.alt() != null && !photo.alt().isBlank()
                ? photo.alt() : photo.photographer() + " photo";
        return new ImageCandidateVO(title, displayUrl, photo.url(), "pexels", 1.0);
    }

    private static String resolveStyle(Map<String, Object> input, String userText) {
        String requested = stringValue(input.get("style"));
        String text = userText != null ? userText.toLowerCase() : "";
        if (containsAny(text, "极简", "minimalist", "minimal")) return "minimalist";
        if (containsAny(text, "写实", "realistic", "photorealistic")) return "realistic";
        return requested;
    }

    private static String resolveDimensions(Map<String, Object> input, String userText) {
        String requested = stringValue(input.get("dimensions"));
        String text = userText != null ? userText.toLowerCase() : "";
        if (containsAny(text, "竖版", "竖向", "portrait", "9:16")) return "768x1344";
        if (containsAny(text, "横版", "横向", "landscape", "16:9")) return "1344x768";
        if (containsAny(text, "方形", "square", "1:1")) return "1024x1024";
        return requested;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text.strip() : "";
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
