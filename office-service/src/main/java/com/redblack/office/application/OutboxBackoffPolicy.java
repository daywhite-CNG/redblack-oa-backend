package com.redblack.office.application;

import java.time.Duration;

public final class OutboxBackoffPolicy {
    private static final Duration MAX = Duration.ofMinutes(5);
    private OutboxBackoffPolicy() { }
    public static Duration forAttempt(int attempts) {
        long seconds = Math.min(1L << Math.min(Math.max(attempts, 0), 8), MAX.toSeconds());
        return Duration.ofSeconds(seconds);
    }
}
