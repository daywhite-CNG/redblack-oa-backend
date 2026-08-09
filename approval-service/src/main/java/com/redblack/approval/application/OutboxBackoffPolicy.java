package com.redblack.approval.application;

import java.time.Duration;

public final class OutboxBackoffPolicy {
    private static final Duration MAXIMUM = Duration.ofMinutes(5);

    private OutboxBackoffPolicy() {
    }

    public static Duration forAttempt(int attempts) {
        int exponent = Math.min(Math.max(attempts, 0), 8);
        Duration value = Duration.ofSeconds(1L << exponent);
        return value.compareTo(MAXIMUM) > 0 ? MAXIMUM : value;
    }
}
