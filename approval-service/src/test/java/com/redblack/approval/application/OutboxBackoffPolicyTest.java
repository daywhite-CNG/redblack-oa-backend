package com.redblack.approval.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffPolicyTest {
    @Test
    void growsExponentiallyAndStopsAtTheConfiguredUpperBound() {
        assertThat(OutboxBackoffPolicy.forAttempt(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxBackoffPolicy.forAttempt(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(OutboxBackoffPolicy.forAttempt(8)).isEqualTo(Duration.ofSeconds(256));
        assertThat(OutboxBackoffPolicy.forAttempt(99)).isEqualTo(Duration.ofSeconds(256));
        assertThat(OutboxBackoffPolicy.forAttempt(-1)).isEqualTo(Duration.ofSeconds(1));
    }
}
