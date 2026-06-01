package com.zzp.aiagent.template.model;

import java.util.List;

public record StyleTemplate(
        String code,
        String name,
        String scene,
        String category,
        List<String> keywords,
        String prompt,
        String negativePrompt,
        String suggestedDimensions
) {
}
