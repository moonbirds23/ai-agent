package com.zzp.aiagent.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <h3>测试目的</h3>
 * 验证 GlobalExceptionHandler 对不同异常的统一处理：BusinessException（含各子错误码）和
 * 未知 Exception 都能转为 BaseResponse，HTTP 200 + JSON body 中携带业务错误码。
 *
 * <h3>实现方式</h3>
 * MockMvc + standaloneSetup(内嵌 TestController + GlobalExceptionHandler)，
 * 无需启动完整 Spring 容器。TestController 的每个 endpoint 抛出特定异常。
 *
 * <h3>关键验证点</h3>
 * - 所有异常的 HTTP 状态码为 200（不对客户端暴露 4xx/5xx）
 * - 业务错误码在 JSON $.code 字段体现
 * - BusinessException.message 直接透传给客户端
 * - 未知异常兜底为 SYSTEM_ERROR(59999)
 */
@DisplayName("GlobalExceptionHandler 统一异常处理")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 目的：验证 CONTENT_BLOCKED 异常被正确转为 BaseResponse。
     * 实现：TestController 抛 BusinessException(CONTENT_BLOCKED) → MockMvc GET /test/business。
     * 结果：HTTP 200，$.code=40200，$.message="请求包含不支持的词汇"。
     */
    @Test
    @DisplayName("BusinessException(CONTENT_BLOCKED) → 200 + code=40200")
    void businessException_shouldReturnErrorCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40200))
                .andExpect(jsonPath("$.message").value("请求包含不支持的词汇"));
    }

    /**
     * 目的：验证 EMPTY_MESSAGE 错误码的独立处理。
     * 实现：抛 BusinessException(EMPTY_MESSAGE) → 断言 code=40100。
     * 结果：HTTP 200，message="消息不能为空"。
     */
    @Test
    @DisplayName("BusinessException(EMPTY_MESSAGE) → 200 + code=40100")
    void emptyMessage_shouldReturn40100() throws Exception {
        mockMvc.perform(get("/test/empty-message"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("消息不能为空"));
    }

    /**
     * 目的：验证 AI 服务相关错误码的处理。
     * 实现：抛 BusinessException(AI_AUTH_FAILED) → 断言 code=50000。
     * 结果：HTTP 200，code=50000，message 透传。
     */
    @Test
    @DisplayName("BusinessException(AI_AUTH_FAILED) → 200 + code=50000")
    void aiApi_shouldReturn50000() throws Exception {
        mockMvc.perform(get("/test/ai-auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("AI 服务鉴权失败"));
    }

    /**
     * 目的：验证记忆存储相关错误的处理。
     * 实现：抛 BusinessException(MEMORY_ERROR) → 断言 code=50010。
     * 结果：HTTP 200，message="对话记忆异常"。
     */
    @Test
    @DisplayName("BusinessException(MEMORY_ERROR) → 200 + code=50010")
    void chatMemory_shouldReturn50010() throws Exception {
        mockMvc.perform(get("/test/memory-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50010))
                .andExpect(jsonPath("$.message").value("对话记忆异常"));
    }

    /**
     * 目的：验证非 BusinessException 的未知异常兜底为 SYSTEM_ERROR。
     * 实现：TestController 抛 RuntimeException → GlobalExceptionHandler 第二条 @ExceptionHandler(Exception.class) 捕获。
     * 结果：HTTP 200，code=59999，message="系统错误"（对客户端隐藏原始异常信息）。
     */
    @Test
    @DisplayName("未知Exception → 200 + code=59999 系统错误")
    void unknown_shouldReturnSystemError() throws Exception {
        mockMvc.perform(get("/test/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(59999))
                .andExpect(jsonPath("$.message").value("系统错误"));
    }

    /**
     * <h3>内嵌测试 Controller</h3>
     * 每个 endpoint 抛出一种特定的异常，供 GlobalExceptionHandler 捕获验证。
     * 不依赖真实业务逻辑，纯粹模拟异常路径。
     */
    @org.springframework.web.bind.annotation.RestController
    static class TestController {
        @org.springframework.web.bind.annotation.GetMapping("/test/business")
        public String throwBusiness() {
            throw new BusinessException(ErrorCode.CONTENT_BLOCKED);
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/empty-message")
        public String throwEmptyMessage() {
            throw new BusinessException(ErrorCode.EMPTY_MESSAGE);
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/ai-auth")
        public String throwAiAuth() {
            throw new BusinessException(ErrorCode.AI_AUTH_FAILED);
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/memory-error")
        public String throwMemoryError() {
            throw new BusinessException(ErrorCode.MEMORY_ERROR);
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/unknown")
        public String throwUnknown() {
            throw new RuntimeException("模拟未知异常");
        }
    }
}
