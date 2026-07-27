package com.zzp.aiagent.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelUsageTest {

    @Test
    void totalIsOnlyAvailableForCompleteUsage() {
        assertThat(new ModelUsage(3L, 4L).totalTokens()).isEqualTo(7L);
        assertThat(new ModelUsage(3L, null).totalTokens()).isNull();
        assertThat(ModelUsage.unavailable().available()).isFalse();
    }

    @Test
    void rejectsNegativeUsage() {
        assertThatThrownBy(() -> new ModelUsage(-1L, 2L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
