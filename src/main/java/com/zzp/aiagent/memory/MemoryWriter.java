package com.zzp.aiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!test")
public class MemoryWriter {

    private final ChatMemory chatMemory;
    private final MemorySanitizer sanitizer;
    private final MemoryClassifier classifier;

    public MemoryWriter(ChatMemory chatMemory, MemorySanitizer sanitizer,
                        MemoryClassifier classifier) {
        this.chatMemory = chatMemory;
        this.sanitizer = sanitizer;
        this.classifier = classifier;
    }

    public void writeUserIntent(String conversationId, String cleanUserText) {
        if (cleanUserText == null || cleanUserText.isBlank()) return;
        if (classifier.classifyUser(cleanUserText)
                != com.zzp.aiagent.memory.model.MemoryEntryType.USER_INTENT) return;
        String sanitized = sanitizer.sanitize(cleanUserText);
        if (sanitized == null || sanitized.isBlank()) return;
        chatMemory.add(conversationId, List.of(new UserMessage(sanitized)));
        log.debug("[MemoryWriter] user intent written conv={}", conversationId);
    }

    public void writeAssistantResponse(String conversationId, String verifiedResponse,
                                       com.zzp.aiagent.agent.task.VerificationResult verification) {
        if (verifiedResponse == null || verifiedResponse.isBlank()) return;
        if (classifier.classifyAssistant(verifiedResponse, verification)
                != com.zzp.aiagent.memory.model.MemoryEntryType.ASSISTANT_FINAL_RESPONSE) return;
        String sanitized = sanitizer.sanitize(verifiedResponse);
        if (sanitized == null || sanitized.isBlank()) return;
        chatMemory.add(conversationId, List.of(new AssistantMessage(sanitized)));
        log.debug("[MemoryWriter] assistant response written conv={}", conversationId);
    }

    /**
     * Writes a completed user/assistant turn in one ChatMemory operation.
     * Failed or unverified turns are deliberately excluded from prompt memory.
     */
    public void writeVerifiedTurn(String conversationId, String userText,
                                  String assistantText,
                                  com.zzp.aiagent.agent.task.VerificationResult verification) {
        if (verification == null || !verification.deliverable()) return;
        if (userText == null || userText.isBlank()
                || assistantText == null || assistantText.isBlank()) return;
        if (classifier.classifyUser(userText)
                != com.zzp.aiagent.memory.model.MemoryEntryType.USER_INTENT) return;
        if (classifier.classifyAssistant(assistantText, verification)
                != com.zzp.aiagent.memory.model.MemoryEntryType.ASSISTANT_FINAL_RESPONSE) return;

        String cleanUser = sanitizer.sanitize(userText);
        String cleanAssistant = sanitizer.sanitize(assistantText);
        if (cleanUser == null || cleanUser.isBlank()
                || cleanAssistant == null || cleanAssistant.isBlank()) return;

        chatMemory.add(conversationId, List.of(
                new UserMessage(cleanUser),
                new AssistantMessage(cleanAssistant)));
        log.debug("[MemoryWriter] verified turn written conv={}", conversationId);
    }

    public void writeResourceSummary(String conversationId, String summary) {
        if (summary == null || summary.isBlank()) return;
        chatMemory.add(conversationId, List.of(new UserMessage("【资源摘要】" + summary)));
        log.debug("[MemoryWriter] resource summary written conv={}", conversationId);
    }
}
