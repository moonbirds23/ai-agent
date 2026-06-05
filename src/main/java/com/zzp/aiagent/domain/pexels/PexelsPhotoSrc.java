package com.zzp.aiagent.domain.pexels;

/**
 * Pexels photo source URLs at different sizes/crops.
 *
 * @param original  Original full-size image
 * @param large2x   Large 2x DPR (940×650)
 * @param large     Large size (940×650)
 * @param medium    Medium size (height 350)
 * @param small     Small size (height 130)
 * @param portrait  Cropped portrait (800×1200)
 * @param landscape Cropped landscape (1200×627)
 * @param tiny      Tiny thumbnail (280×200)
 */
public record PexelsPhotoSrc(
        String original,
        String large2x,
        String large,
        String medium,
        String small,
        String portrait,
        String landscape,
        String tiny
) {}
