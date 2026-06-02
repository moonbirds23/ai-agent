package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.service.VisionAnalysisService;
import org.springframework.stereotype.Service;

@Service
public class NoopVisionAnalysisService implements VisionAnalysisService {

    @Override
    public VisionAnalysisResult analyze(String message, String imageBase64, String imageUrl) {
        throw new BusinessException(ErrorCode.IMAGE_ANALYSIS_FAILED, "图片分析服务未配置，请设置 zhipu.vision.api-key");
    }

    @Override
    public String getProviderName() {
        return "noop-vision-analysis";
    }
}
