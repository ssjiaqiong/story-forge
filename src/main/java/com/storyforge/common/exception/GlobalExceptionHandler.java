package com.storyforge.common.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 全局异常处理。所有异常统一转换为 {@link R} 结构，业务失败一般不返回 4xx/5xx（鉴权与限流除外）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException ex) {
        ErrorCode ec = ex.getErrorCode();
        log.warn("[biz] code={} message={}", ec.getCode(), ex.getMessage());
        return ResponseEntity.status(ec.getHttpStatus()).body(R.fail(ec.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return badRequest(message);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return badRequest(message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必填参数：" + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数类型不正确：" + ex.getName());
    }

    /**
     * 请求体无法反序列化：JSON 语法错误、字段类型不符，或枚举取值非法。
     * 这类问题属于调用方参数错误，必须返回 10001 而不是 90001，否则会误导排查方向。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        String message = ErrorCode.PARAM_INVALID.getMessage();
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty()
                    ? null
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            Class<?> targetType = ife.getTargetType();
            if (targetType != null && targetType.isEnum()) {
                String allowed = Arrays.stream(targetType.getEnumConstants())
                        .map(String::valueOf)
                        .collect(Collectors.joining(" / "));
                message = (field == null ? "" : field + " ") + "取值非法，可选值为 " + allowed;
            } else if (field != null) {
                message = field + " 格式不正确";
            }
        }
        log.warn("[param] 请求体解析失败: {}", ex.getMessage());
        return badRequest(message);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("[forbidden] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(R.fail(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<R<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 静态资源 / 未匹配的接口路径（Spring 6.1+ 由 ResourceHttpRequestHandler 抛出）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception ex, HttpServletRequest request) {
        log.error("[system] uri={} error={}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<R<Void>> badRequest(String message) {
        String msg = (message == null || message.isBlank()) ? ErrorCode.PARAM_INVALID.getMessage() : message;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(ErrorCode.PARAM_INVALID, msg));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
