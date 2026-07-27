package com.zzp.aiagent.exception;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.file.AccessDeniedException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusiness(BusinessException e) {
        log.warn("BusinessException code={} exceptionType={}",
                e.getCode(), e.getClass().getSimpleName());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("@Valid 校验失败: {}", msg);
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(), msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求体格式错误，请检查 JSON 结构");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public BaseResponse<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: {} {}", e.getMethod(), e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求方法不支持: " + e.getMethod());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public BaseResponse<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数: {}", e.getParameterName());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "缺少必要参数: " + e.getParameterName());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public BaseResponse<?> handleNoHandler(Exception e) {
        log.warn("资源不存在: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求的资源不存在");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public BaseResponse<?> handleAccessDenied(AccessDeniedException e) {
        log.warn("访问被拒绝: {}", e.getMessage());
        return ResultUtils.error(40100, "无权访问");
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleException(Exception e) {
        log.error("Exception", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
