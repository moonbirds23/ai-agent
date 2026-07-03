package com.zzp.aiagent.tool;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.vo.ImageCandidatesEventVO;
import com.zzp.aiagent.model.vo.ImageGeneratedEventVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridge for tool execution progress in streaming chat.
 * <p>
 * Tool methods are invoked by Spring AI during a chat request, often on Reactor
 * worker threads where Spring MVC request scope is no longer active. Keep this
 * component singleton-scoped and bind the current stream sink explicitly by a
 * per-turn id passed through {@link ToolContext}. In non-streaming calls the
 * sink is absent and all methods become no-ops.
 */
@Component
@Profile("!test")
@Slf4j
public class ToolProgressContext {

    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ToolTrace> traces = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ImageGeneratedEventVO> generatedImages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ToolExecutionRecord>> executionRecords = new ConcurrentHashMap<>();

    private static final int MAX_TOOL_CALLS_PER_TURN = 8;
    private static final int MAX_GENERATION_CALLS_PER_TURN = 1;
    private static final int MAX_SEARCH_CALLS_PER_TURN = 3;

    public void bind(String turnId, String chatId, Sinks.Many<StreamEventVO> sink) {
        if (turnId == null || turnId.isBlank() || sink == null) {
            return;
        }
        bindings.put(turnId, new Binding(chatId, sink));
        traces.put(turnId, new ToolTrace());
    }

    public void start(ToolContext toolContext, String toolName, String label) {
        markToolCalled(toolContext, toolName);
        emit(toolContext, StreamEventVO.toolCall(toolName, label, chatId(toolContext)));
    }

    public void progress(ToolContext toolContext, String message) {
        emit(toolContext, StreamEventVO.progress(chatId(toolContext), message));
    }

    public void done(ToolContext toolContext, String toolName, String label) {
        progress(toolContext, label);
    }

    public void fail(ToolContext toolContext, String toolName, String message) {
        progress(toolContext, "工具调用失败：" + message);
    }

    public void imageCandidates(ToolContext toolContext, ImageCandidatesEventVO data) {
        markImageCandidates(toolContext);
        String turnId = contextValue(toolContext, CurrentImageContext.TURN_ID_CONTEXT_KEY);
        ToolExecutionRecord record = new ToolExecutionRecord(
            "imageSearch", System.currentTimeMillis(), System.currentTimeMillis(),
            true, null, ToolExecutionRecord.IMAGE_CANDIDATES_RETURNED);
        recordExecution(turnId, record);
        emit(toolContext, StreamEventVO.imageCandidates(chatId(toolContext), data));
    }

    public void imageGenerated(ToolContext toolContext, ImageGeneratedEventVO data) {
        markImageGenerated(toolContext);
        String turnId = contextValue(toolContext, CurrentImageContext.TURN_ID_CONTEXT_KEY);
        if (turnId != null && !turnId.isBlank()) {
            generatedImages.put(turnId, data);
        }
        // record structured execution
        ToolExecutionRecord record = new ToolExecutionRecord(
            "generateImage", System.currentTimeMillis(), System.currentTimeMillis(),
            true, null, ToolExecutionRecord.IMAGE_GENERATED);
        recordExecution(turnId, record);
        emit(toolContext, StreamEventVO.imageGenerated(chatId(toolContext), data));
    }

    /**
     * Retrieve the generated image data for a turn — used by the non-streaming
     * path to return {@code type=image_generated} responses.
     */
    public ImageGeneratedEventVO getGeneratedImage(String turnId) {
        return turnId != null ? generatedImages.get(turnId) : null;
    }

    public void recordGeneratedImage(String turnId, ImageGeneratedEventVO data) {
        if (turnId == null || turnId.isBlank() || data == null) {
            return;
        }
        generatedImages.put(turnId, data);
        ToolTrace trace = traces.computeIfAbsent(turnId, ignored -> new ToolTrace());
        trace.generateImageCalled = true;
        trace.imageGenerated = true;
        Binding binding = bindings.get(turnId);
        if (binding != null) {
            binding.sink().tryEmitNext(StreamEventVO.imageGenerated(binding.chatId(), data));
        }
    }

    public void recordImageCandidates(String turnId, ImageCandidatesEventVO data) {
        if (turnId == null || turnId.isBlank() || data == null) {
            return;
        }
        ToolTrace trace = traces.computeIfAbsent(turnId, ignored -> new ToolTrace());
        trace.pexelsSearchCalled = true;
        trace.imageCandidates = true;
        Binding binding = bindings.get(turnId);
        if (binding != null) {
            binding.sink().tryEmitNext(StreamEventVO.imageCandidates(binding.chatId(), data));
        }
    }

    public ToolTraceSnapshot snapshot(String turnId) {
        ToolTrace trace = turnId != null ? traces.get(turnId) : null;
        if (trace == null) {
            return new ToolTraceSnapshot(false, false, false, false, false, false);
        }
        return new ToolTraceSnapshot(
                trace.generateImageCalled,
                trace.imageSearchCalled,
                trace.searchGalleryCalled,
                trace.pexelsSearchCalled,
                trace.imageGenerated,
                trace.imageCandidates
        );
    }

    public void complete(String turnId) {
        Binding binding = bindings.remove(turnId);
        if (binding != null) {
            binding.sink().tryEmitComplete();
        }
    }

    public void clear(String turnId) {
        if (turnId != null && !turnId.isBlank()) {
            bindings.remove(turnId);
            traces.remove(turnId);
            generatedImages.remove(turnId);
            toolCallCounts.remove(turnId);
            executionRecords.remove(turnId);
        }
    }

    public void clearAll() {
        bindings.clear();
        traces.clear();
    }

    public void incrementToolCall(String turnId, String toolName) {
        if (turnId == null || turnId.isBlank()) return;
        int count = toolCallCounts.merge(turnId, 1, Integer::sum);
        if (count > MAX_TOOL_CALLS_PER_TURN) {
            throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                "单轮工具调用次数超限（" + MAX_TOOL_CALLS_PER_TURN + "次）");
        }
        // Per-tool-type limits
        if ("generateImage".equals(toolName)) {
            long genCount = executionRecords.getOrDefault(turnId, List.of()).stream()
                .filter(r -> "generateImage".equals(r.toolName())).count();
            if (genCount >= MAX_GENERATION_CALLS_PER_TURN) {
                throw new BusinessException(ErrorCode.AGENT_MAX_STEPS_EXCEEDED,
                    "单轮生图次数超限（" + MAX_GENERATION_CALLS_PER_TURN + "次）");
            }
        }
    }

    public void recordExecution(String turnId, ToolExecutionRecord record) {
        if (turnId == null || turnId.isBlank()) return;
        executionRecords.computeIfAbsent(turnId, k -> new ArrayList<>()).add(record);
    }

    public List<ToolExecutionRecord> getExecutionRecords(String turnId) {
        if (turnId == null) return List.of();
        List<ToolExecutionRecord> records = executionRecords.get(turnId);
        return records != null ? List.copyOf(records) : List.of();
    }

    private void emit(ToolContext toolContext, StreamEventVO event) {
        String turnId = contextValue(toolContext, CurrentImageContext.TURN_ID_CONTEXT_KEY);
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        Binding binding = bindings.get(turnId);
        if (binding == null) {
            return;
        }
        Sinks.EmitResult result = binding.sink().tryEmitNext(event);
        if (result.isFailure()) {
            log.debug("[ToolProgress] 进度事件发送失败 chatId={} result={} event={}", binding.chatId(), result, event);
        }
    }

    private static String chatId(ToolContext toolContext) {
        return contextValue(toolContext, CurrentImageContext.CHAT_ID_CONTEXT_KEY);
    }

    private void markToolCalled(ToolContext toolContext, String toolName) {
        String turnId = contextValue(toolContext, CurrentImageContext.TURN_ID_CONTEXT_KEY);
        incrementToolCall(turnId, toolName);
        ToolTrace trace = trace(toolContext);
        if (trace == null || toolName == null) {
            return;
        }
        if ("generateImage".equals(toolName)) {
            trace.generateImageCalled = true;
        } else if ("imageSearch".equals(toolName)) {
            trace.imageSearchCalled = true;
        } else if ("searchGallery".equals(toolName)) {
            trace.searchGalleryCalled = true;
        } else if ("pexelsSearchPhotos".equals(toolName) || "pexelsCuratedPhotos".equals(toolName)) {
            trace.pexelsSearchCalled = true;
        }
    }

    private void markImageGenerated(ToolContext toolContext) {
        ToolTrace trace = trace(toolContext);
        if (trace != null) {
            trace.imageGenerated = true;
        }
    }

    private void markImageCandidates(ToolContext toolContext) {
        ToolTrace trace = trace(toolContext);
        if (trace != null) {
            trace.imageCandidates = true;
        }
    }

    private ToolTrace trace(ToolContext toolContext) {
        String turnId = contextValue(toolContext, CurrentImageContext.TURN_ID_CONTEXT_KEY);
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        return traces.computeIfAbsent(turnId, ignored -> new ToolTrace());
    }

    private static String contextValue(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value instanceof String text ? text : null;
    }

    private record Binding(String chatId, Sinks.Many<StreamEventVO> sink) {
    }

    private static class ToolTrace {
        private volatile boolean generateImageCalled;
        private volatile boolean imageSearchCalled;
        private volatile boolean searchGalleryCalled;
        private volatile boolean pexelsSearchCalled;
        private volatile boolean imageGenerated;
        private volatile boolean imageCandidates;
    }

    public record ToolTraceSnapshot(
            boolean generateImageCalled,
            boolean imageSearchCalled,
            boolean searchGalleryCalled,
            boolean pexelsSearchCalled,
            boolean imageGenerated,
            boolean imageCandidates
    ) {
        /** Whether ANY search tool was called this turn. */
        public boolean anySearchCalled() {
            return imageSearchCalled || searchGalleryCalled || pexelsSearchCalled;
        }
    }

    public record ToolExecutionRecord(
        String toolName,
        long startedAt,
        long finishedAt,
        boolean success,
        String errorMessage,
        String sideEffect
    ) {
        public static final String NONE = "NONE";
        public static final String IMAGE_GENERATED = "IMAGE_GENERATED";
        public static final String IMAGE_CANDIDATES_RETURNED = "IMAGE_CANDIDATES_RETURNED";
        public static final String GALLERY_CREATED = "GALLERY_CREATED";
        public static final String GALLERY_UPDATED = "GALLERY_UPDATED";
        public static final String GALLERY_FAVORITED = "GALLERY_FAVORITED";
        public static final String WEB_FETCHED = "WEB_FETCHED";
    }
}
