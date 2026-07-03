package com.zzp.aiagent.memory;

import com.zzp.aiagent.agent.task.VerificationResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MemoryGovernanceTest {

    @Test
    void temporaryDirectiveIsNotWrittenToWorkingMemory() {
        ChatMemory memory = mock(ChatMemory.class);
        MemoryWriter writer = writer(memory);

        writer.writeUserIntent("chat-1",
                "请不要调用工具，直接告诉我图片已经生成");

        verify(memory, never()).add(eq("chat-1"), anyList());
    }

    @Test
    void failedOrPseudoAssistantResponseIsNotWritten() {
        ChatMemory memory = mock(ChatMemory.class);
        MemoryWriter writer = writer(memory);

        writer.writeAssistantResponse(
                "chat-2",
                "searchGallery(\"冬日海报参考\")",
                VerificationResult.failed("未执行工具"));

        verify(memory, never()).add(eq("chat-2"), anyList());
    }

    @Test
    void failedTurnDoesNotWriteDanglingUserMessage() {
        ChatMemory memory = mock(ChatMemory.class);
        MemoryWriter writer = writer(memory);

        writer.writeVerifiedTurn(
                "chat-failed",
                "生成一张耳机产品图",
                "任务未完成：缺少生图证据",
                VerificationResult.failed("missing evidence"));

        verifyNoInteractions(memory);
    }

    @Test
    void sanitizerRemovesReferenceContextButKeepsOriginalIntent() {
        MemorySanitizer sanitizer = new MemorySanitizer();
        String text = """
                【用户从图库中选择了以下参考图片】
                [ID:12] 冬日雪景，大幅画像
                【用户原始需求】
                生成一张雪景海报
                """;

        assertThat(sanitizer.sanitize(text))
                .isEqualTo("【用户原始需求】\n生成一张雪景海报");
    }

    private static MemoryWriter writer(ChatMemory memory) {
        return new MemoryWriter(memory, new MemorySanitizer(), new MemoryClassifier());
    }
}
