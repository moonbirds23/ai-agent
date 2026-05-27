package com.zzp.aiagent.common;

import com.zzp.aiagent.exception.ErrorCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应封装：HTTP始终200，业务状态在JSON code字段。
 * 只在Controller层使用，App/Service层返回裸VO不包装。
 * 必须保留@NoArgsConstructor：Jackson反序列化需要无参构造器。
 */
@Data
@NoArgsConstructor
public class BaseResponse<T> implements Serializable {

    private int code;
    private T data;
    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
