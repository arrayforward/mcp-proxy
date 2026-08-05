package com.mcpproxy.proxy.web;

/**
 * 业务 API 异常：携带 HTTP 状态码 + 华为风格 error_code。
 *
 * <p>功能：Service/Controller 抛出后由 {@link ApiExceptionHandler} 统一转成
 * {@code {data:null, error_code, error_msg}} 响应体，保持华为 KooPhone 报文兼容。
 *
 * @author hubin
 * @since 2026-08-04
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    public ApiException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
