package com.redblack.common.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record ErrorResponse(
        String code,
        String message,
        List<ErrorDetail> details,
        String requestId,
        OffsetDateTime timestamp
) {
    public ErrorResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        details = details == null ? List.of() : List.copyOf(details);
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}

