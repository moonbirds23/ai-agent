package com.zzp.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.common.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基础速率限制拦截器，基于 ConcurrentHashMap + 滑动窗口的内存实现。
 * 不同接口不同限制：
 *   /api/chat           → 10 次/分钟
 *   /api/gallery/upload → 10 次/分钟
 *   其他                → 30 次/分钟
 * 超限返回 429 + BaseResponse JSON。
 */
@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ConcurrentHashMap<String, List<Long>> counter = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    private static final long WINDOW_MS = 60_000;
    private static final int CHAT_LIMIT = 10;
    private static final int UPLOAD_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 30;
    private static final int RATE_LIMIT_CODE = 42900;

    public RateLimitInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String ip = request.getRemoteAddr();
        String uri = request.getRequestURI();
        String key = ip + ":" + uri;
        long now = System.currentTimeMillis();

        int limit = resolveLimit(uri);

        List<Long> timestamps = counter.computeIfAbsent(key,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (timestamps) {
            timestamps.removeIf(t -> now - t > WINDOW_MS);

            if (timestamps.size() >= limit) {
                log.warn("[RateLimit] 触发限流 ip={} uri={} count={} limit={}",
                        ip, uri, timestamps.size(), limit);
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                BaseResponse<Void> errorResp = new BaseResponse<>(
                        RATE_LIMIT_CODE, null, "请求过于频繁，请稍后再试");
                response.getWriter().write(objectMapper.writeValueAsString(errorResp));
                return false;
            }

            timestamps.add(now);
        }

        return true;
    }

    private int resolveLimit(String uri) {
        if (uri.startsWith("/api/chat")) {
            return CHAT_LIMIT;
        }
        if (uri.startsWith("/api/gallery/upload")) {
            return UPLOAD_LIMIT;
        }
        return DEFAULT_LIMIT;
    }
}
