package com.foodmap.common.exception;

public class GoongIntegrationException extends RuntimeException {

    public GoongIntegrationException(String message) {
        super(message);
    }

    public GoongIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
