package com.redblack.audit.application;

import com.redblack.common.api.ErrorDetail;
import org.springframework.http.HttpStatus;

import java.util.List;

public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }
    public BusinessException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        super(message); this.status = status; this.code = code; this.details = List.copyOf(details);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<ErrorDetail> details() { return details; }
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }
    public static BusinessException denied() {
        return new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足");
    }
}
