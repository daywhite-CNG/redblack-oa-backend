package com.redblack.identity.api;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestIds {
    private RequestIds() {
    }

    public static String get(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
