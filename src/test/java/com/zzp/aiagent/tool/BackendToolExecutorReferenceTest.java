package com.zzp.aiagent.tool;

import com.zzp.aiagent.agent.executor.AgentInput;
import com.zzp.aiagent.agent.task.StepStatus;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.ImageGenerationService;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.service.VisionAnalysisService;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendToolExecutorReferenceTest {

    @Test
    void passesGalleryResultIdsIntoVerifiedGenerationInput() {
        ImageGenerationService generation = mock(ImageGenerationService.class);
        when(generation.generate(any(), any(), any())).thenReturn(
                new ImageGenerationResult("https://generated.example/snow.png",
                        null, "revised", Map.of()));
        BackendToolExecutor executor = new BackendToolExecutor(
                mock(GalleryService.class),
                generation,
                mock(VisionAnalysisService.class),
                mock(StyleTemplateService.class),
                mock(PexelsPhotoService.class),
                mock(ToolProgressContext.class),
                mock(ChatModel.class));

        TaskPlan plan = new TaskPlan("turn-reference", TaskType.CREATIVE_WORKFLOW,
                "snow", List.of(), false, true, false, Map.of());
        AgentInput input = AgentInput.of("snow", "RAG context", List.of(), null,
                Map.of(), "chat-reference", plan);
        ToolExecutionContext context = new ToolExecutionContext(input);
        context.record("search", ToolExecutionRecord.success(
                "turn-reference", "searchGallery", Map.of(),
                Map.of("resultCount", 3, "pictureIds", List.of(127L, 128L, 131L)),
                ToolExecutionRecord.NONE));
        TaskStep generationStep = new TaskStep(
                "generate", "generate", true, "generateImage", List.of("search"),
                Map.of("prompt", "quiet snowy forest at dawn",
                        "style", "realistic", "dimensions", "1024x1024"),
                StepStatus.PENDING);

        ToolExecutionRecord record = executor.execute(
                "turn-reference", generationStep, context);

        assertThat(record.success()).isTrue();
        assertThat(record.input().get("referencePictureIds"))
                .isEqualTo(List.of(127L, 128L, 131L));
    }
}
