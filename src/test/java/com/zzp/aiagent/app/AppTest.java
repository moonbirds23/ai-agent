package com.zzp.aiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

@SpringBootTest
@ActiveProfiles("local")
class AppTest {

    @Resource
    private PictureApp pictureApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是程序员";
        String answer = pictureApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("[第1轮] " + answer);

        // 第二轮
        message = "我想让另一半（ACD）更爱我";
        answer = pictureApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("[第2轮] " + answer);

        // 第三轮
        message = "我的另一半叫什么来着？刚跟你说过，帮我回忆一下";
        answer = pictureApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("[第3轮] " + answer);
    }
}
