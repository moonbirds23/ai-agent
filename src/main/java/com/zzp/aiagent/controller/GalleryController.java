package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryPageResult;
import com.zzp.aiagent.domain.gallery.GalleryUpdateRequest;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.domain.gallery.GalleryQueryRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!test")
@RestController
@RequestMapping("/gallery")
@RequiredArgsConstructor
@Tag(name = "图库管理")
@Slf4j
public class GalleryController {

    private final GalleryService galleryService;

    @PostMapping("/upload")
    @Operation(summary = "上传图片(Base64)", description = "上传Base64编码的图片到图库")
    public BaseResponse<GalleryPicture> upload(@Valid @RequestBody GalleryUploadRequest request) {
        return ResultUtils.success(galleryService.upload(request));
    }

    @PostMapping("/import-url")
    @Operation(summary = "通过URL导入图片", description = "从远程URL下载图片并导入图库")
    public BaseResponse<GalleryPicture> importUrl(@Valid @RequestBody GalleryImportUrlRequest request) {
        return ResultUtils.success(galleryService.importUrl(request));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询图片", description = "按关键字、分类、标签等条件分页查询图库图片")
    public BaseResponse<GalleryPageResult> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "false") boolean favoritedOnly,
            @RequestParam(required = false) String sourceType) {
        GalleryQueryRequest request = new GalleryQueryRequest(
                page, pageSize, keyword, category, tags, favoritedOnly, sourceType);
        return ResultUtils.success(galleryService.listAll(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取图片详情", description = "根据ID获取单张图片的完整信息")
    public BaseResponse<GalleryPicture> getById(@PathVariable Long id) {
        return ResultUtils.success(galleryService.getById(id));
    }

    @PostMapping("/{id}/favorite")
    @Operation(summary = "切换收藏状态", description = "切换指定图片的收藏状态")
    public BaseResponse<GalleryPicture> favorite(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "true") boolean favorited) {
        return ResultUtils.success(galleryService.favorite(id, favorited));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新图片元数据", description = "更新图片的名称、简介、分类和标签")
    public BaseResponse<GalleryPicture> update(@PathVariable Long id,
                                                @RequestBody GalleryUpdateRequest request) {
        return ResultUtils.success(galleryService.update(id,
                request.name(), request.introduction(), request.category(), request.tags()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除图片", description = "删除指定图片及其文件")
    public BaseResponse<String> delete(@PathVariable Long id) {
        galleryService.delete(id);
        return ResultUtils.success("ok");
    }

    @GetMapping("/files/{pictureId}")
    @Operation(summary = "获取图片文件", description = "根据图片ID返回图片文件二进制数据")
    public ResponseEntity<Resource> serveFile(@PathVariable Long pictureId) {
        GalleryPicture picture = galleryService.getById(pictureId);
        byte[] bytes = galleryService.downloadPicture(pictureId);
        String ext = picture.picFormat() != null ? picture.picFormat() : "png";
        InputStreamResource resource = new InputStreamResource(
                new ByteArrayInputStream(bytes));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/" + ext))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                .body(resource);
    }
}
