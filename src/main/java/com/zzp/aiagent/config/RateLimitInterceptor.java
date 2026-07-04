package com.zzp.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.common.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 速率限制拦截器，委托 {@link RedisRateLimiter} 做分布式滑动窗口限流。
 * <p>
 * 不同接口不同限制：
 * <ul>
 *   <li>/api/chat           → 10 次/分钟</li>
 *   <li>/api/gallery/upload → 10 次/分钟</li>
 *   <li>其他                 → 30 次/分钟</li>
 * </ul>
 * Redis 不可用时降级放行，避免单点故障阻断所有请求。
 */
@Component
@Profile("!test")
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    private static final int RATE_LIMIT_CODE = 42900;

    public RateLimitInterceptor(RedisRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String ip = request.getRemoteAddr();
        String uri = request.getRequestURI();

        if (rateLimiter.tryAcquire(ip, uri)) {
            return true;
        }

        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        BaseResponse<Void> errorResp = new BaseResponse<>(
                RATE_LIMIT_CODE, null, "请求过于频繁，请稍后再试");
        response.getWriter().write(objectMapper.writeValueAsString(errorResp));
        return false;
    }
}
