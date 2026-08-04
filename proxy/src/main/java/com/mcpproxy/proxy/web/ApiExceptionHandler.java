package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.BackendException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getHttpStatus()).body(errorBody(e.getErrorCode(), e.getMessage()));
    }

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
