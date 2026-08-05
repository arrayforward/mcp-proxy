package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.BackendException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常 -> 华为风格错误响应的转换器。
 *
 * <p>功能：把所有 Controller 抛出的业务异常统一渲染成
 * {@code {data:null, error_code, error_msg}}，与华为 KooPhone 报文结构一致（api.md §1）。
 *
 * <p>映射表：ApiException -> 自带状态码；BackendException -> 502 KOOPHONE.API.9999。
 *
 * @author hubin
 * @since 2026-08-04
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务异常：按异常内携带的 httpStatus/errorCode 返回 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getHttpStatus()).body(errorBody(e.getErrorCode(), e.getMessage()));
    }

    /** 云机转发失败：502 网关错误 */
    @ExceptionHandler(BackendException.class)
    public ResponseEntity<Map<String, Object>> handleBackend(BackendException e) {
        return ResponseEntity.status(502).body(errorBody("KOOPHONE.API.9999", e.getMessage()));
    }

    private Map<String, Object> errorBody(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", null);
        body.put("error_code", errorCode);
        body.put("error_msg", message);
        return body;
    }
}
