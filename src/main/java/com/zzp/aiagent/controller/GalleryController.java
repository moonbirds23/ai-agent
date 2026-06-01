package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.gallery.model.GalleryImportUrlRequest;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.gallery.model.GalleryQueryRequest;
import com.zzp.aiagent.gallery.model.GalleryUploadRequest;
import com.zzp.aiagent.storage.ObjectStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("!test")
@RestController
@RequestMapping("/gallery")
@RequiredArgsConstructor
@Tag(name = "图库管理")
@Slf4j
public class GalleryController {

    private final GalleryService galleryService;
    private final ObjectStorageService storageService;

    @PostMapping("/upload")
    @Operation(summary = "上传图片(Base64)", description = "上传Base64编码的图片到图库")
    public BaseResponse<GalleryPicture> upload(@RequestBody GalleryUploadRequest request) {
        return ResultUtils.success(galleryService.upload(request));
    }

    @PostMapping("/import-url")
    @Operation(summary = "通过URL导入图片", description = "从远程URL下载图片并导入图库")
    public BaseResponse<GalleryPicture> importUrl(@RequestBody GalleryImportUrlRequest request) {
        return ResultUtils.success(galleryService.importUrl(request));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询图片", description = "按关键字、分类、标签等条件分页查询图库图片")
    public BaseResponse<List<GalleryPicture>> page(
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

    @DeleteMapping("/{id}")
    @Operation(summary = "删除图片", description = "删除指定图片及其文件")
    public BaseResponse<String> delete(@PathVariable Long id) {
        galleryService.delete(id);
        return ResultUtils.success("ok");
    }

    @GetMapping("/files/{pictureId}")
    @Operation(summary = "获取图片文件", description = "根据图片ID返回图片文件二进制数据")
    public ResponseEntity<byte[]> serveFile(@PathVariable Long pictureId) {
        GalleryPicture picture = galleryService.getById(pictureId);
        String ext = picture.picFormat() != null ? picture.picFormat() : "png";

        byte[] bytes = null;
        // storage key 格式: gallery/{userId}/{pictureId}/origin.{ext}（与 GalleryServiceImpl.upload 一致）
        for (String tryExt : List.of(ext, "png", "jpg", "jpeg", "webp", "gif", "bmp")) {
            try {
                String key = "gallery/" + picture.userId() + "/" + pictureId + "/origin." + tryExt;
                bytes = storageService.download(key);
                ext = tryExt;
                break;
            } catch (Exception ignored) {
            }
        }

        if (bytes == null) {
            log.error("[GalleryController] 图片文件不存在 pictureId={}", pictureId);
            throw new com.zzp.aiagent.exception.BusinessException(ErrorCode.GALLERY_OPERATION_FAILED, "图片文件不存在");
        }

        MediaType mediaType = mediaTypeForExt(ext);
        return ResponseEntity.ok().contentType(mediaType).body(bytes);
    }

    private MediaType mediaTypeForExt(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            default -> MediaType.IMAGE_PNG;
        };
    }
}
