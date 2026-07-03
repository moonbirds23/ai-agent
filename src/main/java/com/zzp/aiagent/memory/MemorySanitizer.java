package com.zzp.aiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
public class MemorySanitizer {

    private static final Pattern PSEUDO_TOOL_CALL = Pattern.compile(
            "^\\s*(searchGallery|generateImage|pexelsSearchPhotos|imageSearch|webSearch|downloadImage|importImage|analyzeImage)\\s*\\(.*\\)\\s*$",
            Pattern.MULTILINE);
    private static final Pattern FAKE_IMAGE_MARKDOWN = Pattern.compile(
            "!\\[.*?\\]\\(%s\\)");
    private static final Pattern FAKE_IMAGE_PLACEHOLDER = Pattern.compile(
            "\\[图片链接\\]|\\[image\\]|\\[图片\\d+\\]");
    private static final Pattern SYSTEM_CONTEXT_BLOCK = Pattern.compile(
            "【用户从图库中选择了以下参考图片】[\\s\\S]*?(?=【用户原始需求】|\\z)");

    public String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        String cleaned = text;
        cleaned = PSEUDO_TOOL_CALL.matcher(cleaned).replaceAll("");
        cleaned = FAKE_IMAGE_MARKDOWN.matcher(cleaned).replaceAll("");
        cleaned = FAKE_IMAGE_PLACEHOLDER.matcher(cleaned).replaceAll("");
        cleaned = SYSTEM_CONTEXT_BLOCK.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();
        return cleaned;
    }

    public boolean isPseudoToolCall(String text) {
        return text != null && PSEUDO_TOOL_CALL.matcher(text.strip()).matches();
    }

    public boolean hasFakeImages(String text) {
        return text != null && (FAKE_IMAGE_MARKDOWN.matcher(text).find()
                || FAKE_IMAGE_PLACEHOLDER.matcher(text).find());
    }
}
