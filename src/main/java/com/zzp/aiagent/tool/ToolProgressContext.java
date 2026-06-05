package com.zzp.aiagent.tool;

import com.zzp.aiagent.model.vo.ImageCandidatesEventVO;
import com.zzp.aiagent.model.vo.ImageGeneratedEventVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

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
        emit(toolContext, StreamEventVO.imageCandidates(chatId(toolContext), data));
    }

    public void imageGenerated(ToolContext toolContext, ImageGeneratedEventVO data) {
        markImageGenerated(toolContext);
        String turnId = contextValue(toolContext, CurrentImageContext.TURN_ID_CONTEXT_KEY);
        if (turnId != null && !turnId.isBlank()) {
            generatedImages.put(turnId, data);
        }
        emit(toolContext, StreamEventVO.imageGenerated(chatId(toolContext), data));
    }

    /**
     * Retrieve the generated image data for a turn — used by the non-streaming
     * path to return {@code type=image_generated} responses.
     */
    public ImageGeneratedEventVO getGeneratedImage(String turnId) {
        return turnId != null ? generatedImages.get(turnId) : null;
    }

    public ToolTraceSnapshot snapshot(String turnId) {
        ToolTrace trace = turnId != null ? traces.get(turnId) : null;
        if (trace == null) {
            return new ToolTraceSnapshot(false, false, false, false);
        }
        return new ToolTraceSnapshot(
                trace.generateImageCalled,
                trace.imageSearchCalled,
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
        }
    }

    public void clearAll() {
        bindings.clear();
        traces.clear();
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
        ToolTrace trace = trace(toolContext);
        if (trace == null || toolName == null) {
            return;
        }
        if ("generateImage".equals(toolName)) {
            trace.generateImageCalled = true;
        } else if ("imageSearch".equals(toolName)) {
            trace.imageSearchCalled = true;
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
        private volatile boolean imageGenerated;
        private volatile boolean imageCandidates;
    }

    public record ToolTraceSnapshot(
            boolean generateImageCalled,
            boolean imageSearchCalled,
            boolean imageGenerated,
            boolean imageCandidates
    ) {}
}
