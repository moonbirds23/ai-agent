package com.zzp.aiagent.image;

import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;

public interface VisionAnalysisService {
    VisionAnalysisResult analyze(String message, String imageBase64, String imageUrl);

    String getProviderName();
}
