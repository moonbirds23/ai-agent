package com.zzp.aiagent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（仅非 test profile 生效）。
 * 使用 ApiKeyAuthFilter 对 X-API-Key 请求头做简单校验。
 */
@Configuration
@EnableWebSecurity
@Profile("!test")
@Slf4j
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${app.api-key:}")
    private String configuredApiKey;

    /**
     * 白名单路径：无需 API Key 即可访问。
     */
    private static final String[] PERMIT_ALL_PATHS = {
            "/api/health",
            "/api/health/**",
            "/actuator/**",
            "/api/doc.html",
            "/api/v3/api-docs/**",
            "/api/swagger-ui/**"
    };

    /**
     * 常见前端开发端口。
     */
    private static final List<String> DEV_ORIGINS = List.of(
            "http://localhost:3000",
            "http://localhost:5173"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(configuredApiKey);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .xssProtection(xss -> {})
                        .contentTypeOptions(contentType -> {})
                        .frameOptions(frame -> frame.deny())
                        .cacheControl(cache -> cache.disable())
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PERMIT_ALL_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(apiKeyAuthFilter, BasicAuthenticationFilter.class);

        log.info("SecurityConfig 初始化完成，ApiKeyAuthFilter 已注册（app.api-key={}）",
                (configuredApiKey == null || configuredApiKey.isBlank()) ? "<未配置，鉴权跳过>" : "***");

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            // 开发模式：允许所有来源
            configuration.addAllowedOriginPattern("*");
        } else {
            configuration.setAllowedOrigins(DEV_ORIGINS);
        }

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
