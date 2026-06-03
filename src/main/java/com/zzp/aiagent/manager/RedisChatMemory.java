package com.zzp.aiagent.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.memory.ChatMemoryProperties;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.dto.memory.ChatMessageRecord;
import com.zzp.aiagent.model.dto.memory.ImageRef;
import com.zzp.aiagent.model.dto.memory.MessageRecord;
import com.zzp.aiagent.repository.ChatHistoryRepository;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.VisionAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("!test")
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final TypeReference<List<MessageRecord>> LIST_TYPE = new TypeReference<>() {};
    private static final Pattern GALLERY_URL_PATTERN = Pattern.compile("/gallery/files/(\\d+)");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ChatHistoryRepository historyRepo;
    private final int maxMessages;
    private final Duration ttl;

    private final ObjectProvider<GalleryService> galleryServiceProvider;
    private final ObjectProvider<VisionAnalysisService> visionServiceProvider;
    private final ObjectProvider<ObjectStorageService> storageServiceProvider;
    private final Executor executor;

    public RedisChatMemory(StringRedisTemplate redis,
                           ObjectMapper mapper,
                           ChatHistoryRepository historyRepo,
                           ChatMemoryProperties props,
                           ObjectProvider<GalleryService> galleryServiceProvider,
                           ObjectProvider<VisionAnalysisService> visionServiceProvider,
                           ObjectProvider<ObjectStorageService> storageServiceProvider,
                           @Qualifier("taskExecutor") Executor executor) {
        this.redis = redis;
        this.mapper = mapper;
        this.historyRepo = historyRepo;
        this.maxMessages = props.maxMessages();
        this.ttl = Duration.ofDays(props.ttlDays());
        this.galleryServiceProvider = galleryServiceProvider;
        this.visionServiceProvider = visionServiceProvider;
        this.storageServiceProvider = storageServiceProvider;
        this.executor = executor;
    }

    // ── get ───────────────────────────────────────────────

    @Override
    public List<Message> get(String conversationId) {
        return get(conversationId, maxMessages);
    }

    public List<Message> get(String conversationId, int lastN) {
        int limit = Math.min(lastN, maxMessages);
        String key = key(conversationId);

        // 1. Redis 优先
        List<MessageRecord> records = readFromRedis(key, limit);
        if (!records.isEmpty()) {
            return toMessages(records);
        }

        // 2. PG 回源
        List<ChatMessageRecord> pgRecords = historyRepo.findByConversation(conversationId, limit);
        if (!pgRecords.isEmpty()) {
            List<MessageRecord> restored = pgRecords.stream()
                    .map(ChatMessageRecord::toMessageRecord)
                    .filter(Objects::nonNull)
                    .toList();
            writeToRedis(key, restored);
            return toMessages(restored);
        }

        return Collections.emptyList();
    }

    // ── add ───────────────────────────────────────────────

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 1. 处理图片引用 + 序列化
        List<MessageRecord> records = messages.stream()
                .map(this::toRecord)
                .filter(Objects::nonNull)
                .toList();

        if (records.isEmpty()) {
            return;
        }

        String key = key(conversationId);

        // 2. 同步写 Redis（必须成功）
        writeToRedis(key, records);

        // 3. 截断 Redis List，只保留最近 maxMessages 条
        redis.opsForList().trim(key, -maxMessages, -1);

        // 3. 异步写 PostgreSQL（失败不阻塞）
        CompletableFuture.runAsync(() -> {
            try {
                writeToPostgres(conversationId, records);
            } catch (Exception e) {
                log.warn("[RedisChatMemory] PG写入失败 conv={}: {}", conversationId, e.getMessage());
            }
        }, executor);
    }

    // ── clear ─────────────────────────────────────────────

    @Override
    public void clear(String conversationId) {
        redis.delete(key(conversationId));
        try {
            historyRepo.deleteByConversation(conversationId);
        } catch (Exception e) {
            log.warn("[RedisChatMemory] PG删除失败 conv={}: {}", conversationId, e.getMessage());
        }
        log.debug("[RedisChatMemory] clear conversationId={}", conversationId);
    }

    // ── count ───────────────────────────────────────────────

    /**
     * 返回会话在 Redis List 中的消息数量（同步，无 PG 回退）。
     */
    public int count(String conversationId) {
        Long size = redis.opsForList().size(key(conversationId));
        return size != null ? size.intValue() : 0;
    }

    // ── Redis 读写 ─────────────────────────────────────────

    private void writeToRedis(String key, List<MessageRecord> records) {
        List<String> jsonList = records.stream()
                .map(r -> {
                    try { return mapper.writeValueAsString(r); }
                    catch (Exception e) { throw new RuntimeException("序列化 MessageRecord 失败", e); }
                })
                .toList();
        redis.opsForList().rightPushAll(key, jsonList);
        redis.expire(key, ttl);
    }

    private List<MessageRecord> readFromRedis(String key, int lastN) {
        Long len = redis.opsForList().size(key);
        if (len == null || len <= 0) {
            return Collections.emptyList();
        }
        long start = Math.max(0, len - lastN);
        List<String> jsonList = redis.opsForList().range(key, start, len - 1);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }
        return jsonList.stream()
                .map(json -> {
                    try { return mapper.readValue(json, MessageRecord.class); }
                    catch (Exception e) { throw new RuntimeException("反序列化 MessageRecord 失败", e); }
                })
                .toList();
    }

    // ── PG 写入 ────────────────────────────────────────────

    private void writeToPostgres(String conversationId, List<MessageRecord> records) {
        for (MessageRecord r : records) {
            historyRepo.save(new ChatMessageRecord(
                    conversationId, r.role(), r.content(),
                    r.imageRefs(), Map.of()));
        }
    }

    // ── 序列化: Message → MessageRecord ────────────────────

    private MessageRecord toRecord(Message msg) {
        List<ImageRef> imageRefs = null;

        if (msg instanceof UserMessage um && um.getMedia() != null && !um.getMedia().isEmpty()) {
            imageRefs = new ArrayList<>();
            for (Media media : um.getMedia()) {
                ImageRef ref = resolveImageRef(media);
                if (ref != null) {
                    imageRefs.add(ref);
                }
            }
            if (imageRefs.isEmpty()) {
                imageRefs = null;
            }
        }

        return new MessageRecord(msg.getMessageType().name(), msg.getText(), imageRefs);
    }

    private ImageRef resolveImageRef(Media media) {
        Object data = media.getData();

        // 分支1: URI → 检查是否为图库地址
        if (data instanceof URI uri) {
            Long pictureId = extractGalleryPictureId(uri.toString());
            if (pictureId != null && verifyPictureExists(pictureId)) {
                return ImageRef.gallery(pictureId);
            }
            // 非图库外部 URL → 尝试视觉分析
            return analyzeExternalImage(media);
        }

        // 分支2: ByteArrayResource (base64) → 外部图片
        if (data instanceof ByteArrayResource bar) {
            return analyzeExternalImage(media);
        }

        // 分支3: String → 尝试匹配图库模式
        if (data instanceof String s) {
            Long pictureId = extractGalleryPictureId(s);
            if (pictureId != null && verifyPictureExists(pictureId)) {
                return ImageRef.gallery(pictureId);
            }
            return analyzeExternalImage(media);
        }

        return null;
    }

    private Long extractGalleryPictureId(String url) {
        if (url == null) return null;
        Matcher m = GALLERY_URL_PATTERN.matcher(url);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean verifyPictureExists(Long pictureId) {
        try {
            GalleryService gs = galleryServiceProvider.getIfAvailable();
            if (gs == null) return false;
            gs.getById(pictureId);
            return true;
        } catch (Exception e) {
            log.debug("[RedisChatMemory] 图库图片不存在 pictureId={}", pictureId);
            return false;
        }
    }

    private ImageRef analyzeExternalImage(Media media) {
        VisionAnalysisService vs = visionServiceProvider.getIfAvailable();
        if (vs == null) {
            return null;  // 没有视觉服务，跳过
        }

        try {
            byte[] bytes = extractBytes(media);
            if (bytes == null || bytes.length == 0) return null;

            String base64 = "data:" + guessContentType(media) + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes);
            VisionAnalysisResult result = vs.analyze("请简要描述这张图片的内容", base64, null);

            String description = buildShortDescription(result);
            if (description != null && !description.isBlank()) {
                log.debug("[RedisChatMemory] 外部图片分析完成: {}", description);
                return ImageRef.textDescription(description);
            }
        } catch (Exception e) {
            log.warn("[RedisChatMemory] 外部图片分析失败: {}", e.getMessage());
        }
        return null;
    }

    private byte[] extractBytes(Media media) {
        Object data = media.getData();
        if (data instanceof ByteArrayResource bar) {
            return bar.getByteArray();
        }
        // URI: 尝试下载（简单处理，仅处理 base64 data URL）
        if (data instanceof URI uri) {
            String s = uri.toString();
            if (s.startsWith("data:image/")) {
                int comma = s.indexOf(',');
                if (comma >= 0) {
                    try {
                        return Base64.getDecoder().decode(s.substring(comma + 1));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private String guessContentType(Media media) {
        if (media.getMimeType() != null) {
            return media.getMimeType().toString();
        }
        return "image/png";
    }

    private String buildShortDescription(VisionAnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.subject() != null && !result.subject().isBlank()) {
            sb.append(result.subject());
        }
        if (result.style() != null && !result.style().isBlank()) {
            if (!sb.isEmpty()) sb.append("，");
            sb.append(result.style()).append("风格");
        }
        if (result.colors() != null && !result.colors().isBlank()) {
            if (!sb.isEmpty()) sb.append("，");
            sb.append(result.colors()).append("色调");
        }
        if (sb.isEmpty() && result.imagePrompt() != null) {
            sb.append(result.imagePrompt());
        }
        return sb.toString();
    }

    // ── 反序列化: MessageRecord → Message ──────────────────

    private List<Message> toMessages(List<MessageRecord> records) {
        return records.stream()
                .map(this::toMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    private Message toMessage(MessageRecord record) {
        return switch (record.role()) {
            case MessageRecord.ROLE_USER -> buildUserMessage(record);
            case MessageRecord.ROLE_ASSISTANT -> new AssistantMessage(record.content());
            case MessageRecord.ROLE_SYSTEM -> new SystemMessage(record.content());
            default -> throw new IllegalArgumentException("未知 role: " + record.role());
        };
    }

    private UserMessage buildUserMessage(MessageRecord record) {
        List<Media> media = restoreMediaFromImageRefs(record.imageRefs());

        if (media != null && !media.isEmpty()) {
            return UserMessage.builder()
                    .text(record.content())
                    .media(media)
                    .build();
        }

        // 无 GALLERY 可还原 → 将 TEXT_DESCRIPTION 拼入 content
        String content = buildContentWithDescriptions(record);
        return new UserMessage(content);
    }

    private List<Media> restoreMediaFromImageRefs(List<ImageRef> imageRefs) {
        if (imageRefs == null || imageRefs.isEmpty()) {
            return null;
        }

        List<Media> restored = new ArrayList<>();
        for (ImageRef ref : imageRefs) {
            if (ImageRef.TYPE_GALLERY.equals(ref.type()) && ref.pictureId() != null) {
                Media media = rebuildGalleryMedia(ref.pictureId());
                if (media != null) {
                    restored.add(media);
                }
            }
            // TEXT_DESCRIPTION: 不还原 Media，由 buildContentWithDescriptions 处理
        }

        return restored.isEmpty() ? null : restored;
    }

    private Media rebuildGalleryMedia(Long pictureId) {
        try {
            GalleryService gs = galleryServiceProvider.getIfAvailable();
            ObjectStorageService ss = storageServiceProvider.getIfAvailable();
            if (gs == null || ss == null) return null;

            GalleryPicture picture = gs.getById(pictureId);
            byte[] bytes = ss.download(picture.storageKey());

            String ext = picture.picFormat() != null ? picture.picFormat() : "png";
            return new Media(MimeTypeUtils.parseMimeType("image/" + ext),
                    new ByteArrayResource(bytes));
        } catch (Exception e) {
            log.debug("[RedisChatMemory] 图库图片还原失败 pictureId={}: {}", pictureId, e.getMessage());
            return null;
        }
    }

    private static String buildContentWithDescriptions(MessageRecord record) {
        if (record.imageRefs() == null || record.imageRefs().isEmpty()) {
            return record.content();
        }

        StringBuilder descriptions = new StringBuilder();
        for (ImageRef ref : record.imageRefs()) {
            if (ImageRef.TYPE_TEXT_DESCRIPTION.equals(ref.type()) && ref.description() != null) {
                if (!descriptions.isEmpty()) descriptions.append("；");
                descriptions.append(ref.description());
            }
        }

        if (descriptions.isEmpty()) {
            return record.content();
        }

        return "用户在此前的对话中发过图片，图片描述：" + descriptions
                + "。当前用户消息：" + (record.content() != null ? record.content() : "");
    }

    // ── 工具 ───────────────────────────────────────────────

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
