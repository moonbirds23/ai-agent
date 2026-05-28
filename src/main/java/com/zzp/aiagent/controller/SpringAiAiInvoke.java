package com.zzp.aiagent.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
public class SpringAiAiInvoke implements CommandLineRunner {

    @Resource
    private ChatModel openAiChatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage output = openAiChatModel.call(new Prompt("你好"))
                .getResult()
                .getOutput();
        log.info("[SpringAiAiInvoke] 启动探测完成: {}", output.getText());
    }
}
