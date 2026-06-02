package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.dto.image.ImageGenerationResult;
import com.zzp.aiagent.service.ImageGenerationService;
import org.springframework.stereotype.Service;

/**
 * 默认生图服务：未配置真实 API 时返回明确错误提示，不阻塞应用启动。
 * 后续接入真实 API 时用 @Primary 标注新实现即可覆盖。
 */
@Service
public class NoopImageGenerationService implements ImageGenerationService {

    @Override
    public ImageGenerationResult generate(String prompt, String style, String dimensions) {
        throw new BusinessException(ErrorCode.IMAGE_GENERATION_FAILED, "图片生成服务未配置，请设置 aiagent.image.generation.provider");
    }

    @Override
    public String getProviderName() {
        return "noop";
    }
}
