package com.zzp.aiagent.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetrySanitizerTest {

    private final TelemetrySanitizer sanitizer = new TelemetrySanitizer();

    @Test
    void redactsContentBearingAndCredentialAttributes() {
        assertThat(sanitizer.sanitizeAttribute("tool.arguments", "{\"token\":\"secret\"}"))
                .isEqualTo(TelemetrySanitizer.REDACTED);
        assertThat(sanitizer.sanitizeAttribute("gen_ai.prompt", "private prompt"))
                .isEqualTo(TelemetrySanitizer.REDACTED);
        assertThat(sanitizer.sanitizeAttribute("authorization", "Bearer abc"))
                .isEqualTo(TelemetrySanitizer.REDACTED);
    }

    @Test
    void stripsSignedUrlQueryAndEmbeddedBase64() {
        assertThat(sanitizer.sanitizeAttribute("image.url",
                "https://cdn.example/image.png?signature=secret&expires=1"))
                .isEqualTo("https://cdn.example/image.png");
        assertThat(sanitizer.sanitizeAttribute("image.summary",
                "data:image/png;base64,abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/abcdefghijklmnopqrstuvwxyz"))
                .doesNotContain("abcdefghijklmnopqrstuvwxyz")
                .contains(TelemetrySanitizer.REDACTED);
    }

    @Test
    void textSummaryContainsOnlyLengthAndStableHash() {
        String summary = sanitizer.summarizeText("sensitive value");

        assertThat(summary).startsWith("length=15,sha256=");
        assertThat(summary).doesNotContain("sensitive value");
    }
}
