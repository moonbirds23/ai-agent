package com.zzp.aiagent.observability;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitizes the small summaries allowed in telemetry. Raw prompts, completions,
 * tool payloads and credentials must not be passed to observations at all.
 */
@Component
public class TelemetrySanitizer {

    public static final String REDACTED = "[REDACTED]";
    private static final int MAX_ATTRIBUTE_LENGTH = 256;
    private static final Pattern DATA_URL = Pattern.compile("(?i)data:[^;\\s]+;base64,[a-z0-9+/=]+");
    private static final Pattern LONG_BASE64 = Pattern.compile("(?i)(?:[a-z0-9+/]{80,}={0,2})");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]+");
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "authorization", "cookie", "password", "secret", "apikey", "api_key",
            "token", "prompt", "completion", "argument", "result", "base64", "content"
    );

    public String sanitizeAttribute(String key, Object value) {
        if (value == null) {
            return "null";
        }
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        String text = value.toString();
        text = stripUrlQuery(text);
        text = DATA_URL.matcher(text).replaceAll(REDACTED);
        text = LONG_BASE64.matcher(text).replaceAll(REDACTED);
        text = BEARER.matcher(text).replaceAll("Bearer " + REDACTED);
        return truncate(text);
    }

    public String summarizeText(String value) {
        if (value == null) {
            return "length=0";
        }
        return "length=" + value.length() + ",sha256=" + sha256(value);
    }

    public String errorType(Throwable error) {
        return error == null ? "none" : error.getClass().getName();
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private String stripUrlQuery(String text) {
        try {
            URI uri = URI.create(text);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return text;
            }
            return new URI(uri.getScheme(), uri.getUserInfo() == null ? null : REDACTED,
                    uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return text.replaceAll("(?i)(https?://[^?\\s]+)\\?[^\\s]+", "$1");
        }
    }

    private String truncate(String value) {
        return value.length() <= MAX_ATTRIBUTE_LENGTH
                ? value
                : value.substring(0, MAX_ATTRIBUTE_LENGTH) + "…";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
