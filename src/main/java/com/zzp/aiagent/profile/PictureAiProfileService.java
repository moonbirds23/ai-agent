package com.zzp.aiagent.profile;

import com.zzp.aiagent.profile.model.PictureAiProfile;

import java.util.List;

public interface PictureAiProfileService {
    PictureAiProfile analyze(Long pictureId);
    PictureAiProfile getByPictureId(Long pictureId);
    List<PictureAiProfile> listByPictureIds(List<Long> pictureIds);
    void index(Long pictureId);
    void removeIndex(Long pictureId);
}
