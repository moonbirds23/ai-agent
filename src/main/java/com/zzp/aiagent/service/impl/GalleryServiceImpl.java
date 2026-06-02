package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.domain.gallery.GalleryQueryRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.ImageDownloadService;
import com.zzp.aiagent.event.GalleryPictureSavedEvent;
import com.zzp.aiagent.model.dto.image.DownloadedImage;
import com.zzp.aiagent.repository.GalleryPictureRepository;
import com.zzp.aiagent.manager.ObjectStorageService;
import com.zzp.aiagent.domain.storage.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

@Profile("!test")
@Service
@Slf4j
public class GalleryServiceImpl implements GalleryService {

    private final GalleryPictureRepository repository;
    private final ImageDownloadService imageDownloadService;
    private final ObjectStorageService storageService;
    private final ApplicationEventPublisher eventPublisher;

    public GalleryServiceImpl(GalleryPictureRepository repository,
                              ImageDownloadService imageDownloadService,
                              ObjectStorageService storageService,
                              ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.imageDownloadService = imageDownloadService;
        this.storageService = storageService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public GalleryPicture upload(GalleryUploadRequest request) {
        ThrowUtils.throwIf(request.imageBase64() == null || request.imageBase64().isBlank(),
                ErrorCode.PARAMS_ERROR, "图片数据不能为空");
        ThrowUtils.throwIf(request.name() == null || request.name().isBlank(),
                ErrorCode.PARAMS_ERROR, "图片名称不能为空");

        Base64Image decoded = decodeBase64(request.imageBase64());
        ThrowUtils.throwIf(!isImageFormat(decoded.contentType()), ErrorCode.IMAGE_FORMAT_INVALID,
                "不支持的图片格式: " + decoded.contentType());

        // 哈希去重：相同文件直接返回已有记录
        String picHash = sha256(decoded.bytes());
        List<GalleryPicture> existing = repository.findByHash(picHash);
        if (!existing.isEmpty()) {
            log.info("[GalleryService] 重复图片跳过上传，返回已有记录 id={} hash={}",
                    existing.get(0).id(), picHash);
            return existing.get(0);
        }

        // Create the picture record first (without id) to get dimensions
        ImageMeta meta = readImageMeta(decoded.bytes(), decoded.contentType());
        String ext = extFromContentType(decoded.contentType());

        String storageLocation = request.storageLocation() != null
                ? request.storageLocation() : StorageLocation.MAIN;
        GalleryPicture picture = new GalleryPicture(
                null,                          // id -> assigned by repository
                null,                          // url -> set after saving
                null,                          // thumbnailUrl
                request.name(),
                request.introduction(),
                request.category(),
                request.tags() != null ? new ArrayList<>(request.tags()) : Collections.emptyList(),
                (long) decoded.bytes().length, // picSize
                meta.width(),
                meta.height(),
                meta.scale(),
                ext,                           // picFormat
                1L,                            // userId
                0L,                            // spaceId
                1,                             // reviewStatus
                null,                          // picColor
                "upload",                      // sourceType
                request.favorited() != null ? request.favorited() : false,
                LocalDateTime.now(),
                LocalDateTime.now(),
                storageLocation,
                picHash
        );

        GalleryPicture saved = repository.save(picture);

        // Save image via object storage
        String key = saved.storageKey();
        StoredObject stored = storageService.upload(decoded.bytes(), key,
                contentTypeFromExt(ext));

        // Update url from storage service
        GalleryPicture withUrl = new GalleryPicture(
                saved.id(), stored.url(), saved.thumbnailUrl(), saved.name(),
                saved.introduction(), saved.category(), saved.tags(),
                saved.picSize(), saved.picWidth(), saved.picHeight(),
                saved.picScale(), saved.picFormat(), saved.userId(),
                saved.spaceId(), saved.reviewStatus(), saved.picColor(),
                saved.sourceType(), saved.favorited(),
                saved.createTime(), saved.updateTime(),
                saved.storageLocation(),
                saved.picHash()
        );
        repository.save(withUrl);

        log.info("[GalleryService] 上传成功 id={} name={} size={} format={}",
                saved.id(), saved.name(), saved.picSize(), ext);
        eventPublisher.publishEvent(new GalleryPictureSavedEvent(withUrl, decoded.bytes(), contentTypeFromExt(ext), request.imageBase64()));
        return withUrl;
    }

    @Override
    @Transactional
    public GalleryPicture importUrl(GalleryImportUrlRequest request) {
        ThrowUtils.throwIf(request.imageUrl() == null || request.imageUrl().isBlank(),
                ErrorCode.PARAMS_ERROR, "图片URL不能为空");
        ThrowUtils.throwIf(request.name() == null || request.name().isBlank(),
                ErrorCode.PARAMS_ERROR, "图片名称不能为空");

        DownloadedImage downloaded = imageDownloadService.download(request.imageUrl());

        // 哈希去重
        String picHash = sha256(downloaded.bytes());
        List<GalleryPicture> existing = repository.findByHash(picHash);
        if (!existing.isEmpty()) {
            log.info("[GalleryService] 重复图片跳过导入，返回已有记录 id={} hash={}",
                    existing.get(0).id(), picHash);
            return existing.get(0);
        }

        ImageMeta meta = readImageMeta(downloaded.bytes(), downloaded.contentType());
        String ext = extFromContentType(downloaded.contentType());

        GalleryPicture picture = new GalleryPicture(
                null,
                null,
                null,
                request.name(),
                request.introduction(),
                request.category(),
                request.tags() != null ? new ArrayList<>(request.tags()) : Collections.emptyList(),
                (long) downloaded.bytes().length,
                meta.width(),
                meta.height(),
                meta.scale(),
                ext,
                1L,
                0L,
                1,
                null,
                "import_url",
                false,
                LocalDateTime.now(),
                LocalDateTime.now(),
                StorageLocation.MAIN,
                picHash
        );

        GalleryPicture saved = repository.save(picture);

        String key = saved.storageKey();
        StoredObject stored = storageService.upload(downloaded.bytes(), key,
                contentTypeFromExt(ext));

        GalleryPicture withUrl = new GalleryPicture(
                saved.id(), stored.url(), saved.thumbnailUrl(), saved.name(),
                saved.introduction(), saved.category(), saved.tags(),
                saved.picSize(), saved.picWidth(), saved.picHeight(),
                saved.picScale(), saved.picFormat(), saved.userId(),
                saved.spaceId(), saved.reviewStatus(), saved.picColor(),
                saved.sourceType(), saved.favorited(),
                saved.createTime(), saved.updateTime(),
                saved.storageLocation(),
                saved.picHash()
        );
        repository.save(withUrl);

        log.info("[GalleryService] URL导入成功 id={} name={} url={}",
                saved.id(), saved.name(), request.imageUrl());
        eventPublisher.publishEvent(new GalleryPictureSavedEvent(withUrl, downloaded.bytes(), downloaded.contentType(), null));
        return withUrl;
    }

    @Override
    public List<GalleryPicture> listAll(GalleryQueryRequest request) {
        List<GalleryPicture> all = repository.findAll();

        int page = request.page() != null && request.page() > 0 ? request.page() : 1;
        int pageSize = request.pageSize() != null && request.pageSize() > 0 ? request.pageSize() : 20;

        // Filtering
        if (request.keyword() != null && !request.keyword().isBlank()) {
            String kw = request.keyword().toLowerCase();
            all = all.stream()
                    .filter(p -> (p.name() != null && p.name().toLowerCase().contains(kw))
                            || (p.introduction() != null && p.introduction().toLowerCase().contains(kw)))
                    .collect(Collectors.toList());
        }
        if (request.category() != null && !request.category().isBlank()) {
            all = all.stream()
                    .filter(p -> request.category().equals(p.category()))
                    .collect(Collectors.toList());
        }
        if (request.tags() != null && !request.tags().isEmpty()) {
            all = all.stream()
                    .filter(p -> p.tags() != null && !Collections.disjoint(p.tags(), request.tags()))
                    .collect(Collectors.toList());
        }
        if (request.favoritedOnly() != null && request.favoritedOnly()) {
            all = all.stream()
                    .filter(p -> Boolean.TRUE.equals(p.favorited()))
                    .collect(Collectors.toList());
        }
        if (request.sourceType() != null && !request.sourceType().isBlank()) {
            all = all.stream()
                    .filter(p -> request.sourceType().equals(p.sourceType()))
                    .collect(Collectors.toList());
        }

        // Paginate
        int from = (page - 1) * pageSize;
        if (from >= all.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(from + pageSize, all.size());
        return all.subList(from, to);
    }

    @Override
    public GalleryPicture getById(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "图片ID无效");
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAMS_ERROR, "图片不存在: " + id));
    }

    @Override
    public List<GalleryPicture> listByIds(List<Long> ids) {
        ThrowUtils.throwIf(ids == null || ids.isEmpty(), ErrorCode.PARAMS_ERROR, "图片ID列表不能为空");
        return repository.findByIds(ids);
    }

    @Override
    public GalleryPicture favorite(Long id, boolean favorited) {
        GalleryPicture existing = getById(id);
        GalleryPicture updated = new GalleryPicture(
                existing.id(), existing.url(), existing.thumbnailUrl(), existing.name(),
                existing.introduction(), existing.category(), existing.tags(),
                existing.picSize(), existing.picWidth(), existing.picHeight(),
                existing.picScale(), existing.picFormat(), existing.userId(),
                existing.spaceId(), existing.reviewStatus(), existing.picColor(),
                existing.sourceType(), favorited,
                existing.createTime(), LocalDateTime.now(),
                existing.storageLocation(),
                existing.picHash()
        );
        return repository.save(updated);
    }

    @Override
    public void delete(Long id) {
        GalleryPicture existing = getById(id);
        repository.deleteById(id);
        storageService.delete(existing.storageKey());

        log.info("[GalleryService] 删除成功 id={}", id);
    }

    @Override
    public byte[] downloadPicture(Long pictureId) {
        GalleryPicture picture = getById(pictureId);
        byte[] bytes = null;
        String[] tryExts = {picture.picFormat(), "png", "jpg", "jpeg", "webp", "gif", "bmp"};
        for (String tryExt : tryExts) {
            if (tryExt == null || tryExt.isBlank()) continue;
            try {
                bytes = storageService.download(picture.storageKey());
                if (bytes != null && bytes.length > 0) return bytes;
            } catch (Exception ignored) {
            }
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.GALLERY_OPERATION_FAILED, "图片文件不存在: " + pictureId);
        }
        return bytes;
    }

    @Override
    public List<GalleryPicture> searchByKeyword(String query, int limit) {
        return repository.searchByKeyword(query, limit);
    }

    private static String contentTypeFromExt(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    // ---- Base64 helpers ----

    private Base64Image decodeBase64(String imageBase64) {
        String data = imageBase64.trim();
        String contentType = "image/png";
        byte[] bytes;

        if (data.startsWith("data:")) {
            int commaIdx = data.indexOf(',');
            if (commaIdx < 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "无效的Base64数据格式");
            }
            String header = data.substring(5, commaIdx);
            if (header.endsWith(";base64")) {
                contentType = header.substring(0, header.length() - 7);
            }
            data = data.substring(commaIdx + 1);
        }

        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Base64解码失败");
        }

        return new Base64Image(bytes, contentType);
    }

    private record Base64Image(byte[] bytes, String contentType) {}

    // ---- Image metadata helpers ----

    private ImageMeta readImageMeta(byte[] bytes, String contentType) {
        int width = 0;
        int height = 0;
        double scale = 1.0;

        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            try {
                BufferedImage image = ImageIO.read(bis);
                if (image != null) {
                    width = image.getWidth();
                    height = image.getHeight();
                    scale = height > 0 ? ((double) width / height) : 1.0;
                }
            } catch (IOException e) {
                log.warn("[GalleryService] 无法读取图片尺寸，使用默认值 contentType={}", contentType);
            }
        } catch (IOException e) {
            // ByteArrayInputStream close should never throw
        }

        return new ImageMeta(width, height, scale);
    }

    private record ImageMeta(int width, int height, double scale) {}

    private boolean isImageFormat(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        return ct.startsWith("image/");
    }

    private String extFromContentType(String contentType) {
        if (contentType == null) return "png";
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> "png";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
