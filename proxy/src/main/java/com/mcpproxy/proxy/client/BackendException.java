package com.mcpproxy.proxy.client;

public class BackendException extends RuntimeException {

    public BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
