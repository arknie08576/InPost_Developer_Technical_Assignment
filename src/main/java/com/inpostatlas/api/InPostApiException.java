package com.inpostatlas.api;

public class InPostApiException extends RuntimeException {
    public InPostApiException(String message) {
        super(message);
    }

    public InPostApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
