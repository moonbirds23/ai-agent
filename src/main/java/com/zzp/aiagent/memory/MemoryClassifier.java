package com.zzp.aiagent.memory;

import com.zzp.aiagent.agent.task.VerificationResult;
import com.zzp.aiagent.memory.model.MemoryEntryType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class MemoryClassifier {

    private static final Pattern PSEUDO_TOOL_CALL = Pattern.compile(
            "^\\s*(searchGallery|generateImage|pexelsSearchPhotos|imageSearch|webSearch|downloadImage|importImage|analyzeImage)\\s*\\(.*\\)\\s*$");
    private static final Pattern FAKE_IMAGE = Pattern.compile(
            "!\\[.*?\\]\\(%s\\)|\\[图片链接\\]|\\[image\\]|!\\[.*?\\]\\(https?://[^)]*\\s*\\)");
    private static final Set<String> TEMP_DIRECTIVE_KEYWORDS = Set.of(
            "不要调用工具", "不要使用工具", "不要搜索", "直接告诉我", "别调用", "跳过工具");

    public MemoryEntryType classifyUser(String content) {
        if (content == null || content.isBlank()) return MemoryEntryType.USER_INTENT;
        String lower = content.toLowerCase(Locale.ROOT);
        for (String kw : TEMP_DIRECTIVE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return MemoryEntryType.TEMPORARY_DIRECTIVE;
            }
        }
        if (lower.startsWith("【用户从图库中选择了以下参考图片】")
                || lower.startsWith("【系统参考上下文】")
                || lower.startsWith("【rag")) {
            return MemoryEntryType.SYSTEM_CONTEXT;
        }
        return MemoryEntryType.USER_INTENT;
    }

    public MemoryEntryType classifyAssistant(String content, VerificationResult verification) {
        if (content == null || content.isBlank()) return MemoryEntryType.ASSISTANT_FINAL_RESPONSE;
        if (PSEUDO_TOOL_CALL.matcher(content.strip()).matches()) {
            return MemoryEntryType.FAILED_DELIVERY;
        }
        if (FAKE_IMAGE.matcher(content).find()) {
            return MemoryEntryType.FAILED_DELIVERY;
        }
        if (verification != null && !verification.deliverable()) {
            return MemoryEntryType.FAILED_DELIVERY;
        }
        return MemoryEntryType.ASSISTANT_FINAL_RESPONSE;
    }

    public MemoryEntryType classifySystem(String content) {
        return MemoryEntryType.SYSTEM_CONTEXT;
    }
}
