package com.zzp.aiagent.advisor;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.util.List;
import java.util.Set;

public class ContentGuardAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    // 双接口实现：非流式和流式共用validate()，避免校验逻辑分叉

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final List<String> BLOCKED_KEYWORDS = List.of("暴力", "色情", "政治敏感");
    private static final int MAX_IMAGES_PER_REQUEST = 5;
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("png", "jpeg", "jpg", "webp", "gif");

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        validate(request);
        return chain.nextAroundCall(request);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest request, StreamAroundAdvisorChain chain) {
        // validate()同步抛异常会穿透.onErrorResume()（Flux尚未组装完毕），
        // 必须转为Flux.error()让异常进入响应式流，ExceptionGuardAdvisor才能兜底
        try {
            validate(request);
        } catch (BusinessException e) {
            return Flux.error(e);
        }
        return chain.nextAroundStream(request);
    }

    private void validate(AdvisedRequest request) {
        String message = request.userText();

        ThrowUtils.throwIf(message == null || message.isBlank(), ErrorCode.EMPTY_MESSAGE);
        ThrowUtils.throwIf(message.length() > MAX_MESSAGE_LENGTH, ErrorCode.MESSAGE_TOO_LONG,
                "当前长度 " + message.length() + "，最大允许 " + MAX_MESSAGE_LENGTH);
        String lower = message.toLowerCase();
        for (String keyword : BLOCKED_KEYWORDS) {
            ThrowUtils.throwIf(lower.contains(keyword), ErrorCode.CONTENT_BLOCKED,
                    "消息包含违规词汇: " + keyword);
        }
        validateImages(request.messages());
    }

    private void validateImages(List<Message> messages) {
        if (messages == null) return;
        int totalImages = 0;
        for (Message msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            var media = um.getMedia();
            if (media == null || media.isEmpty()) continue;
            totalImages += media.size();
            ThrowUtils.throwIf(totalImages > MAX_IMAGES_PER_REQUEST, ErrorCode.IMAGE_TOO_LARGE,
                    "单次最多上传 " + MAX_IMAGES_PER_REQUEST + " 张图片");

            for (var m : media) {
                Object data = m.getData();
                ThrowUtils.throwIf(data == null, ErrorCode.IMAGE_FORMAT_INVALID, "图片数据为空");
                long size = -1;
                if (data instanceof byte[] bytes) {
                    // Spring AI 内部会把 ByteArrayResource 读成 byte[] 存储，这是实际生效的分支
                    size = bytes.length;
                } else if (data instanceof Resource res) {
                    // 兜底：Spring AI 升级后若改回 Resource 类型，校验仍生效
                    try {
                        size = res.contentLength();
                    } catch (java.io.IOException e) {
                        // contentLength 不可用则跳过大小校验
                    }
                }
                if (size > 0) {
                    ThrowUtils.throwIf(size > MAX_IMAGE_BYTES, ErrorCode.IMAGE_TOO_LARGE,
                            "图片大小 " + (size / 1024 / 1024) + "MB，最大允许 10MB");
                }
                // URL 类型不做深度校验，仅检查非空
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
