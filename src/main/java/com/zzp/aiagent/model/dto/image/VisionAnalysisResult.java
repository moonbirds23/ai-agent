package com.zzp.aiagent.model.dto.image;

public record VisionAnalysisResult(
        String message,
        String subject,
        String scene,
        String style,
        String colors,
        String composition,
        String lighting,
        String mood,
        String imagePrompt
) {
    public String detailText() {
        StringBuilder sb = new StringBuilder();
        append(sb, "主体", subject);
        append(sb, "场景", scene);
        append(sb, "风格", style);
        append(sb, "色彩", colors);
        append(sb, "构图", composition);
        append(sb, "光影", lighting);
        append(sb, "情绪氛围", mood);
        append(sb, "可复用 Prompt", imagePrompt);
        return sb.toString().trim();
    }

    public String memoryText() {
        String details = detailText();
        if (details.isBlank()) {
            return message;
        }
        if (message == null || message.isBlank()) {
            return details;
        }
        return message + "\n\n" + details;
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(label).append("：").append(value);
    }
}
