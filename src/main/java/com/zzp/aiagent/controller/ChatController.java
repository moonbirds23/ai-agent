package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import com.zzp.aiagent.model.vo.StreamEventVO;
import com.zzp.aiagent.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Profile("!test")
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "AI对话", description = "云图库AI图片生成对话接口")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @Operation(summary = "非流式对话", description = "发送消息并等待完整结构化回复")
    public BaseResponse<ChatResponseVO> chat(@Valid @RequestBody ChatRequest request) {
        // @Valid校验失败会抛MethodArgumentNotValidException → Spring默认返回400(非200+BaseResponse)
        // 如需统一返回格式，需在GlobalExceptionHandler中显式拦截该异常
        String chatId = request.chatId() != null ? request.chatId() : UUID.randomUUID().toString();
        return ResultUtils.success(chatService.chat(request, chatId));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(SSE)", description = "SSE流式推送，首事件为chatId，中间为token，结尾为done")
    // SSE是持续连接，无法用BaseResponse包装：首条data:发出后HTTP首部已定，错误通过流内StreamEventVO(type="error")传递
    public Flux<StreamEventVO> chatStream(@Valid @RequestBody ChatRequest request) {
        String chatId = request.chatId() != null ? request.chatId() : UUID.randomUUID().toString();
        return chatService.chatStream(request, chatId);
    }
}
