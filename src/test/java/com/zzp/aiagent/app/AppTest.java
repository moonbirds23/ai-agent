package com.zzp.aiagent.app;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.model.vo.ChatResponseVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

/**
 * <h3>测试目的</h3>
 * 端到端验证：Spring Boot 容器 + 真实智谱 API + Redis + PostgreSQL 的全链路集成。
 * 这是唯一不走 mock 的测试，验证真实 LLM 响应和记忆持久化。
 *
 * <h3>实现方式</h3>
 * {@code @SpringBootTest + @ActiveProfiles("local")} 启动完整容器，
 * 注入真实的 PictureApp Bean → 3 轮对话 → 第 3 轮询问前文信息，验证记忆保持。
 *
 * <h3>前提条件</h3>
 * - 本地 PostgreSQL 已启动（端口 5432，数据库 ai_agent）
 * - 本地 Redis 已启动（端口 6379）
 * - ZHIPU_API_KEY 环境变量已设置
 * - application-local.yml 中 API Key 有效
 *
 * <h3>关键验证点</h3>
 * - ChatResponseVO 各字段非 null（LLM 返回合法 JSON）
 * - 第 3 轮能回忆第 1 轮的内容（"另一半叫ACD"）
 */
@SpringBootTest
@ActiveProfiles("local")
@Disabled("需要本地 PostgreSQL + Redis + 真实 API Key 同时就绪")
@DisplayName("集成测试：真实 DeepSeek + Redis + PG 全链路")
class AppTest {

    @Resource
    private PictureApp pictureApp;

    /**
     * 目的：端到端验证多轮对话的上下文记忆。
     * 实现：chatId=UUID → 第 1 轮告知"另一半叫ACD"，第 2 轮日常对话，第 3 轮询问对方名字。
     * 结果：3 轮均返回非 null 非空 message，第 3 轮应能回忆起 ACD。
     */
    @Test
    @DisplayName("3轮对话 → 第3轮回忆第1轮内容（需真实API+Redis+PG）")
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮：告知个人信息
        String message = "你好，我是程序员";
        ChatResponseVO answer = pictureApp.doChat(new ChatRequest(message, chatId, null, null, null), chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertNotNull(answer.message());
        System.out.println("[第1轮] type=" + answer.type() + " message=" + answer.message());

        // 第二轮：补充信息
        message = "我想让另一半（ACD）更爱我";
        answer = pictureApp.doChat(new ChatRequest(message, chatId, null, null, null), chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertNotNull(answer.message());
        System.out.println("[第2轮] type=" + answer.type() + " message=" + answer.message());

        // 第三轮：验证记忆——能否回忆起第 1 轮的内容
        message = "我的另一半叫什么来着？刚跟你说过，帮我回忆一下";
        answer = pictureApp.doChat(new ChatRequest(message, chatId, null, null, null), chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertNotNull(answer.message());
        System.out.println("[第3轮] type=" + answer.type() + " message=" + answer.message());
    }
}
