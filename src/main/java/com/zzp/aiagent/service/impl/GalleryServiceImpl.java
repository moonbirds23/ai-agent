package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryPageResult;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.domain.gallery.GalleryQueryRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.HybridGalleryRetriever;
import com.zzp.aiagent.service.ImageDownloadService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.model.dto.image.DownloadedImage;
import com.zzp.aiagent.repository.GalleryPictureRepository;
import com.zzp.aiagent.manager.ObjectStorageService;
import com.zzp.aiagent.domain.storage.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Profile("!test")
@Service
@Slf4j
public class GalleryServiceImpl implements GalleryService {

    private final GalleryPictureRepository repository;
    private final ImageDownloadService imageDownloadService;
    private final ObjectStorageService storageService;
    private final PictureAiProfileService profileService;
    private final HybridGalleryRetriever hybridRetriever;
    private final Executor executor;

    public GalleryServiceImpl(GalleryPictureRepository repository,
                              ImageDownloadService imageDownloadService,
                              ObjectStorageService storageService,
                              PictureAiProfileService profileService,
                              HybridGalleryRetriever hybridRetriever,
                              @Qualifier("taskExecutor") Executor executor) {
        this.repository = repository;
        this.imageDownloadService = imageDownloadService;
        this.storageService = storageService;
        this.profileService = profileService;
        this.hybridRetriever = hybridRetriever;
        this.executor = executor;
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
        GalleryPicture picture = GalleryPicture.forUpload(
                request.name(), request.introduction(), request.category(),
                request.tags(),
                (long) decoded.bytes().length,
                meta.width(), meta.height(), meta.scale(),
                ext,
                "upload",
                storageLocation,
                picHash
        );
        if (request.favorited() != null) {
            picture = picture.withFavorite(request.favorited());
        }

        GalleryPicture saved;
        try {
            saved = repository.save(picture);
        } catch (DuplicateKeyException e) {
            // Lost race — another thread inserted the same hash between our check and insert
            List<GalleryPicture> dup = repository.findByHash(picHash);
            if (!dup.isEmpty()) {
                log.info("[GalleryService] 并发去重命中，返回已有记录 id={} hash={}",
                        dup.get(0).id(), picHash);
                return dup.get(0);
            }
            throw e; // shouldn't happen — constraint violation but findByHash returns nothing
        }

        // Save image via object storage
        String key = saved.storageKey();
        StoredObject stored = storageService.upload(decoded.bytes(), key,
                contentTypeFromExt(ext));

        // Update url from storage service
        GalleryPicture withUrl = saved.withUrl(stored.url());
        repository.save(withUrl);

        log.info("[GalleryService] 上传成功 id={} name={} size={} format={}",
                saved.id(), saved.name(), saved.picSize(), ext);
        // Schedule AI analysis after commit to release DB connection (P2 fix)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> {
                    try {
                        profileService.analyzeDirectWithBase64(withUrl, request.imageBase64(),
                                contentTypeFromExt(ext));
                    } catch (Exception e) {
                        log.warn("[GalleryService] 自动画像分析失败 pictureId={}, 标记为待重试",
                                saved.id(), e);
                    }
                }, executor);
            }
        });
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

        GalleryPicture picture = GalleryPicture.forUpload(
                request.name(), request.introduction(), request.category(),
                request.tags(),
                (long) downloaded.bytes().length,
                meta.width(), meta.height(), meta.scale(),
                ext,
                "import_url",
                StorageLocation.MAIN,
                picHash
        );

        GalleryPicture saved = repository.save(picture);

        String key = saved.storageKey();
        StoredObject stored = storageService.upload(downloaded.bytes(), key,
                contentTypeFromExt(ext));

        GalleryPicture withUrl = saved.withUrl(stored.url());
        repository.save(withUrl);

        log.info("[GalleryService] URL导入成功 id={} name={} url={}",
                saved.id(), saved.name(), request.imageUrl());
        // Schedule AI analysis after commit to release DB connection (P2 fix)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> {
                    try {
                        profileService.analyzeDirect(withUrl, downloaded.bytes(),
                                downloaded.contentType());
                    } catch (Exception e) {
                        log.warn("[GalleryService] 自动画像分析失败 pictureId={}, 标记为待重试",
                                saved.id(), e);
                    }
                }, executor);
            }
        });
        return withUrl;
    }

    @Override
    public GalleryPageResult listAll(GalleryQueryRequest request) {
        int page = request.page() != null && request.page() > 0 ? request.page() : 1;
        int pageSize = request.pageSize() != null && request.pageSize() > 0 ? request.pageSize() : 20;
        if (pageSize > 100) pageSize = 100;

        int offset = (page - 1) * pageSize;
        List<GalleryPicture> records = repository.findAllPaged(
                offset, pageSize, request.keyword(), request.category(),
                request.tags(), request.favoritedOnly(), request.sourceType());
        long total = repository.countFiltered(
                request.keyword(), request.category(), request.tags(),
                request.favoritedOnly(), request.sourceType());

        return new GalleryPageResult(records, total, page, pageSize);
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
        GalleryPicture updated = existing.withFavorite(favorited);
        return repository.save(updated);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        GalleryPicture existing = getById(id);

        // 删除向量索引 + 画像数据
        try {
            profileService.removeIndex(id);
        } catch (Exception e) {
            log.warn("[GalleryService] 删除画像/向量失败 pictureId={}, 继续删除图库记录", id, e);
        }

        repository.deleteById(id);

        // 事务提交后再删对象存储文件，避免删了文件但事务回滚导致数据不一致
        String storageKey = existing.storageKey();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storageService.delete(storageKey);
            }
        });

        log.info("[GalleryService] 删除成功 id={}", id);
    }

    @Override
    public byte[] downloadPicture(Long pictureId) {
        GalleryPicture picture = getById(pictureId);
        byte[] bytes = storageService.download(picture.storageKey());
        if (bytes != null && bytes.length > 0) return bytes;
        throw new BusinessException(ErrorCode.GALLERY_OPERATION_FAILED, "图片文件不存在: " + pictureId);
    }

    @Override
    public List<GalleryPicture> search(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        RagSearchCriteria criteria = new RagSearchCriteria(
                query, null, null, null, null, null, false, null,
                limit * 2, limit, 0.3);
        return hybridRetriever.retrieve(criteria).stream()
                .map(RagCandidate::picture)
                .distinct()
                .limit(limit)
                .toList();
    }

    @Override
    public GalleryPicture update(Long id, String name, String introduction,
                                  String category, List<String> tags) {
        GalleryPicture existing = getById(id);
        String newName = (name != null && !name.isBlank()) ? name : existing.name();
        String newIntro = (introduction != null && !introduction.isBlank()) ? introduction : existing.introduction();
        String newCat = (category != null && !category.isBlank()) ? category : existing.category();
        List<String> newTags = (tags != null && !tags.isEmpty()) ? tags : existing.tags();
        GalleryPicture updated = existing.withMeta(newName, newIntro, newCat, newTags);
        return repository.save(updated);
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
