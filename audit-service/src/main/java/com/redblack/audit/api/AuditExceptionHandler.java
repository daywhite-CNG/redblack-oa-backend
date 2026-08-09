package com.redblack.audit.api;

import com.redblack.audit.application.BusinessException;
import com.redblack.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.List;

@RestControllerAdvice
public class AuditExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AuditExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.details(), request);
    }
    @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", List.of(), request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled audit request {}", RequestIds.get(request), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求", List.of(), request);
    }
    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message,
                                                   List<com.redblack.common.api.ErrorDetail> details,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, details,
                RequestIds.get(request), OffsetDateTime.now()));
    }
}
