package com.zzp.aiagent.exception;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    // 未拦截MethodArgumentNotValidException（@Valid校验失败），会走Spring默认处理返回HTTP 400
    // 如需统一返回BaseResponse(40000)，需显式添加该异常的@ExceptionHandler

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusiness(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleException(Exception e) {
        log.error("Exception", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
