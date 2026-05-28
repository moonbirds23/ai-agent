package com.zzp.aiagent.advisor;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

public class ContentGuardAdvisor implements CallAdvisor, StreamAdvisor {
    // 双接口实现：非流式和流式共用validate()，避免校验逻辑分叉

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final List<String> BLOCKED_KEYWORDS = List.of("暴力", "色情", "政治敏感");
    private static final int MAX_IMAGES_PER_REQUEST = 5;
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("png", "jpeg", "jpg", "webp", "gif");

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        validate(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        try {
            validate(request);
        } catch (BusinessException e) {
            return Flux.error(e);
        }
        return chain.nextStream(request);
    }

    private void validate(ChatClientRequest request) {
        UserMessage userMsg = request.prompt().getUserMessage();
        String message = userMsg != null ? userMsg.getText() : "";

        ThrowUtils.throwIf(message == null || message.isBlank(), ErrorCode.EMPTY_MESSAGE);
        ThrowUtils.throwIf(message.length() > MAX_MESSAGE_LENGTH, ErrorCode.MESSAGE_TOO_LONG,
                "当前长度 " + message.length() + "，最大允许 " + MAX_MESSAGE_LENGTH);
        String lower = message.toLowerCase();
        for (String keyword : BLOCKED_KEYWORDS) {
            ThrowUtils.throwIf(lower.contains(keyword), ErrorCode.CONTENT_BLOCKED,
                    "消息包含违规词汇: " + keyword);
        }
        validateImages(request.prompt().getInstructions());
    }

    private void validateImages(List<Message> messages) {
        if (messages == null) return;
        int totalImages = 0;
        for (Message msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            List<Media> media = um.getMedia();
            if (media == null || media.isEmpty()) continue;
            totalImages += media.size();
            ThrowUtils.throwIf(totalImages > MAX_IMAGES_PER_REQUEST, ErrorCode.IMAGE_TOO_LARGE,
                    "单次最多上传 " + MAX_IMAGES_PER_REQUEST + " 张图片");

            for (Media m : media) {
                long size = -1;
                if (m.getData() instanceof byte[] bytes) {
                    size = bytes.length;
                }
                if (size > 0) {
                    ThrowUtils.throwIf(size > MAX_IMAGE_BYTES, ErrorCode.IMAGE_TOO_LARGE,
                            "图片大小 " + (size / 1024 / 1024) + "MB，最大允许 10MB");
                }
            }
        }
    }

    @Override
    public String getName() {
        return "ContentGuard";
    }

    @Override
    public int getOrder() {
        return 0;   // 最优先：在任何业务逻辑（含记忆检索）之前拦截非法输入
    }
}
