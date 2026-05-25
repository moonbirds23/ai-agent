package com.zzp.aiagent.controller;

import com.zzp.aiagent.app.PictureApp;
import com.zzp.aiagent.dto.ChatRequest;
import com.zzp.aiagent.dto.ChatResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Profile("!test")
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "AI对话", description = "云图库AI图片生成对话接口")
public class ChatController {

    private final PictureApp pictureApp;

    @PostMapping
    @Operation(summary = "非流式对话", description = "发送消息并等待完整回复，支持多轮对话（传入相同chatId保持上下文）")
    public ChatResult chat(@RequestBody ChatRequest request) {
        String chatId = request.chatId() != null ? request.chatId() : UUID.randomUUID().toString();
        String content = pictureApp.doChat(request.message(), chatId);
        return new ChatResult(content, chatId);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(SSE)", description = "发送消息，以Server-Sent Events方式逐token推送回复")
    public Flux<String> chatStream(
            @Parameter(description = "用户消息") @RequestParam String message,
            @Parameter(description = "会话ID，不传则自动生成") @RequestParam(required = false) String chatId) {
        String cid = chatId != null ? chatId : UUID.randomUUID().toString();
        return pictureApp.doChatStream(message, cid);
    }
}
