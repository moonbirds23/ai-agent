package com.zzp.aiagent.service;

import com.zzp.aiagent.model.dto.image.ImageGenerationResult;

/**
 * 图片生成服务接口，provider-agnostic。
 * 默认使用 {@link NoopImageGenerationService}，真实实现后续按需接入。
 */
public interface ImageGenerationService {

    ImageGenerationResult generate(String prompt, String style, String dimensions);

    String getProviderName();
}
