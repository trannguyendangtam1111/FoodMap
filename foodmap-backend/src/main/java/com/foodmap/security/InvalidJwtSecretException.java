package com.foodmap.security;

/**
 * The configured JWT secret cannot be used. Thrown while the application
 * starts, so a bad secret stops startup instead of failing the first login.
 * The message never contains the secret itself.
 */
public class InvalidJwtSecretException extends IllegalStateException {

    public InvalidJwtSecretException(String message) {
        super(message);
    }
}
