package com.mcpproxy.proxy.client;

/**
 * 云机后端调用失败异常。
 *
 * <p>功能：HttpMcpBackendClient 所有转发失败的统一包装，
 * 由 ApiExceptionHandler 映射为 HTTP 502 + KOOPHONE.API.9999。
 *
 * @author hubin
 */
public class BackendException extends RuntimeException {

    public BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
