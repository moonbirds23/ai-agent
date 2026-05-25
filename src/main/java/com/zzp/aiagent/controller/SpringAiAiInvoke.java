package com.zzp.aiagent.controller;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
        System.out.println(output.getText());
    }
}
