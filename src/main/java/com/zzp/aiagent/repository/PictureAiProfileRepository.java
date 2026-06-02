package com.zzp.aiagent.repository;

import com.zzp.aiagent.model.entity.PictureAiProfile;

import java.util.List;
import java.util.Optional;

public interface PictureAiProfileRepository {
    PictureAiProfile save(PictureAiProfile profile);
    Optional<PictureAiProfile> findByPictureId(Long pictureId);
    List<PictureAiProfile> findByPictureIds(List<Long> pictureIds);
    void deleteByPictureId(Long pictureId);
}
