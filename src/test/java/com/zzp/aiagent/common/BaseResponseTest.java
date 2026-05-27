package com.zzp.aiagent.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 BaseResponse 和 ResultUtils 的构建逻辑与 Jackson 序列化/反序列化正确性。
 * 这是统一响应规范的核心——所有接口返回体都遵循 {code, data, message} 三元组。
 *
 * <h3>实现方式</h3>
 * - 直接调用 ResultUtils.success/error 四个重载方法
 * - ObjectMapper 序列化/反序列化 BaseResponse
 *
 * <h3>关键验证点</h3>
 * - success 的 code 固定为 0，message 固定为 "ok"
 * - error 的 data 始终为 null
 * - 序列化后 JSON 字段顺序可预测
 * - 反序列化需要 @NoArgsConstructor（Jackson 约束）
 */
@DisplayName("BaseResponse 与 ResultUtils")
class BaseResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 目的：验证 ResultUtils.success(data) 三个字段的标准值。
     * 实现：success("hello") → 断言 code=0, message="ok", data="hello"。
     * 结果：三字段与 Picture-Backend 规范一致。
     */
    @Test
    @DisplayName("success → code=0, message=ok, data=原始值")
    void success_shouldHaveCodeZero() {
        BaseResponse<String> response = ResultUtils.success("hello");

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getMessage()).isEqualTo("ok");
        assertThat(response.getData()).isEqualTo("hello");
    }

    /**
     * 目的：验证 BaseResponse 能正确序列化为预期 JSON 格式。
     * 实现：success("ok") → ObjectMapper.writeValueAsString → 断言三个字段均出现。
     * 结果：JSON 包含 "code":0、"data":"ok"、"message":"ok"。
     */
    @Test
    @DisplayName("success 序列化 → JSON 含全三个字段")
    void success_shouldSerializeToJson() throws Exception {
        BaseResponse<String> response = ResultUtils.success("ok");
        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"code\":0");
        assertThat(json).contains("\"data\":\"ok\"");
        assertThat(json).contains("\"message\":\"ok\"");
    }

    /**
     * 目的：验证 JSON 字符串能反序列化回 BaseResponse 对象。
     * 原因：Jackson 反序列化需要 @NoArgsConstructor，此处验证该注解有效。
     * 实现：构造标准 JSON → readValue(BaseResponse.class) → 断言 code 和 message。
     * 结果：反序列化后 code=0, message="ok"。
     */
    @Test
    @DisplayName("JSON 反序列化 → BaseResponse（验证 @NoArgsConstructor 有效）")
    void success_shouldDeserializeFromJson() throws Exception {
        String json = "{\"code\":0,\"data\":\"test\",\"message\":\"ok\"}";

        BaseResponse<?> response = mapper.readValue(json, BaseResponse.class);

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getMessage()).isEqualTo("ok");
    }

    /**
     * 目的：验证 ResultUtils.error(int code, String msg) 构建错误响应。
     * 实现：error(40100, "...") → 断言 code=40100, data=null, message 正确。
     * 结果：data 为 null，错误码和 message 透传。
     */
    @Test
    @DisplayName("error(code, msg) → data=null, code/message 透传")
    void error_byCode_shouldHaveNonNullCode() {
        BaseResponse<?> response = ResultUtils.error(40100, "消息不能为空");

        assertThat(response.getCode()).isEqualTo(40100);
        assertThat(response.getMessage()).isEqualTo("消息不能为空");
        assertThat(response.getData()).isNull();
    }

    /**
     * 目的：验证 ResultUtils.error(ErrorCode) 从枚举提取 code 和 message。
     * 实现：error(SYSTEM_ERROR) → 断言 code=59999, message="系统内部异常"。
     * 结果：错误响应与 ErrorCode 枚举值完全对应。
     */
    @Test
    @DisplayName("error(ErrorCode) → code/message 来自枚举")
    void error_byErrorCode_shouldMatch() {
        BaseResponse<?> response = ResultUtils.error(ErrorCode.SYSTEM_ERROR);

        assertThat(response.getCode()).isEqualTo(59999);
        assertThat(response.getMessage()).isEqualTo("系统内部异常");
        assertThat(response.getData()).isNull();
    }

    /**
     * 目的：验证 ResultUtils.error(ErrorCode, String) 的 message 覆盖重载。
     * 实现：error(SYSTEM_ERROR, "自定义错误信息") → 断言 code 取枚举值但 message 被覆盖。
     * 结果：code=59999，message="自定义错误信息"。
     */
    @Test
    @DisplayName("error(ErrorCode, 自定义msg) → code 取枚举，message 被覆盖")
    void error_byErrorCodeAndMessage_shouldUseCustomMessage() {
        BaseResponse<?> response = ResultUtils.error(ErrorCode.SYSTEM_ERROR, "自定义错误信息");

        assertThat(response.getCode()).isEqualTo(59999);
        assertThat(response.getMessage()).isEqualTo("自定义错误信息");
    }
}
