package com.zzp.aiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.common.BaseResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API Key 鉴权过滤器。
 * 读取 X-API-Key 请求头与 app.api-key 配置值比对。
 * 未配置 Key 时跳过鉴权（开发友好），配置后强制校验。
 * 不作为 Spring Bean 自动注册，由 SecurityConfig 手动实例化。
 */
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String configuredApiKey;

    public ApiKeyAuthFilter(String configuredApiKey) {
        this.configuredApiKey = configuredApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 未配置 Key：开发模式，跳过鉴权
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            if (log.isWarnEnabled()) {
                log.warn("app.api-key 未配置，API Key 鉴权已禁用，所有请求直接放行（仅开发环境安全）");
            }
            filterChain.doFilter(request, response);
            return;
        }

        String requestApiKey = request.getHeader(API_KEY_HEADER);

        if (configuredApiKey.equals(requestApiKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Key 不匹配：返回 401
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        BaseResponse<?> errorResponse = new BaseResponse<>(40100, null, "API Key 无效");
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(errorResponse));
    }
}
