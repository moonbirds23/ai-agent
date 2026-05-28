package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import com.zzp.aiagent.profile.PictureAiProfileService;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!test")
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
@Tag(name = "图片AI画像")
public class ProfileController {

    private final PictureAiProfileService profileService;

    @PostMapping("/{pictureId}/analyze")
    public BaseResponse<PictureAiProfile> analyze(@PathVariable Long pictureId) {
        return ResultUtils.success(profileService.analyze(pictureId));
    }

    @GetMapping("/{pictureId}")
    public BaseResponse<PictureAiProfile> getByPictureId(@PathVariable Long pictureId) {
        return ResultUtils.success(profileService.getByPictureId(pictureId));
    }

    @PostMapping("/{pictureId}/reindex")
    public BaseResponse<String> reindex(@PathVariable Long pictureId) {
        profileService.index(pictureId);
        return ResultUtils.success("索引成功");
    }
}
