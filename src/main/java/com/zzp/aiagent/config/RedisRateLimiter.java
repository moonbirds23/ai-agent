package com.zzp.aiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Redis 分布式滑动窗口限流器。
 * <p>
 * 使用 Lua 脚本保证原子性，适用于多实例部署场景。
 * 基于 sorted set：key = {@code rate_limit:<ip>:<path>}，
 * score = 毫秒时间戳，窗口外的记录先清理再统计。
 */
@Slf4j
@Component
@Profile("!test")
public class RedisRateLimiter {

    private final StringRedisTemplate redis;

    private static final long WINDOW_MS = 60_000;
    private static final int CHAT_LIMIT = 10;
    private static final int UPLOAD_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 30;

    private static final String KEY_PREFIX = "rate_limit:";

    /**
     * Lua 脚本：原子性地清理过期记录、添加当前请求、检查是否超限。
     * KEYS[1] — sorted set key
     * ARGV[1] — 窗口起始时间 (now - windowMs)
     * ARGV[2] — 当前时间戳 (now)
     * ARGV[3] — 限制数量
     * ARGV[4] — 窗口毫秒数 (用于 TTL)
     * 返回值：1 = 允许，0 = 拒绝
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local windowStart = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            -- 清理窗口外的过期记录
            redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)

            -- 统计窗口内请求数
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end

            -- 记录本次请求 + 设 TTL
            redis.call('ZADD', key, now, now .. ':' .. count)
            redis.call('PEXPIRE', key, ttl)
            return 1
            """;

    private final DefaultRedisScript<Long> script;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    /**
     * 检查是否允许本次请求。
     *
     * @param ip  客户端 IP
     * @param uri 请求路径
     * @return true = 放行，false = 限流拒绝
     */
    public boolean tryAcquire(String ip, String uri) {
        String key = KEY_PREFIX + ip + ":" + normalizePath(uri);
        long now = Instant.now().toEpochMilli();
        long windowStart = now - WINDOW_MS;
        int limit = resolveLimit(uri);

        try {
            Long result = redis.execute(script, List.of(key),
                    String.valueOf(windowStart),
                    String.valueOf(now),
                    String.valueOf(limit),
                    String.valueOf(WINDOW_MS * 2)); // TTL 设为窗口的两倍，留余量
            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                log.warn("[RateLimit] Redis 限流触发 ip={} uri={} limit={}", ip, uri, limit);
            }
            return allowed;
        } catch (Exception e) {
            // Redis 不可用时降级放行，避免阻断所有请求
            log.error("[RateLimit] Redis 不可用，降级放行 ip={} uri={}", ip, uri, e);
            return true;
        }
    }

    private static int resolveLimit(String uri) {
        if (uri.startsWith("/api/chat")) return CHAT_LIMIT;
        if (uri.startsWith("/api/gallery/upload")) return UPLOAD_LIMIT;
        return DEFAULT_LIMIT;
    }

    /**
     * 去掉 context-path 前缀和尾部斜杠，统一计数口径。
     */
    private static String normalizePath(String uri) {
        if (uri == null) return "/";
        String path = uri.replaceFirst("^/api", "");
        if (path.isEmpty()) path = "/";
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
