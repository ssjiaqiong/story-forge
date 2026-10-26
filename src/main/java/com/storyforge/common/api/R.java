package com.storyforge.common.api;

import lombok.Data;
import org.slf4j.MDC;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <pre>{ "code": 0, "message": "ok", "data": {}, "traceId": "..." }</pre>
 *
 * @param <T> 业务数据类型
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), "ok", data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return build(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return build(errorCode.getCode(), message == null ? errorCode.getMessage() : message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return build(code, message, null);
    }

    public static <T> R<T> build(int code, String message, T data) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.data = data;
        r.traceId = MDC.get("traceId");
        return r;
    }
}
