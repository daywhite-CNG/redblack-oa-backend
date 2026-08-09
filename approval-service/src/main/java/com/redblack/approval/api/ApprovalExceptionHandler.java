package com.redblack.approval.api;

import com.redblack.approval.application.BusinessException;
import com.redblack.common.api.ErrorDetail;
import com.redblack.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.List;

@RestControllerAdvice
public class ApprovalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApprovalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::detail)
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", details, request);
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", List.of(), request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidBinding(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", List.of(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "数据已发生变化，请刷新后重试",
                List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled approval request {}", RequestIds.get(request), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法处理请求", List.of(), request);
    }

    private ErrorDetail detail(FieldError error) {
        return new ErrorDetail(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status,
                                                   String code,
                                                   String message,
                                                   List<ErrorDetail> details,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                code, message, details, RequestIds.get(request), OffsetDateTime.now()));
    }
}
