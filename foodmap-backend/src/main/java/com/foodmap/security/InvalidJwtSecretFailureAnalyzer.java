package com.foodmap.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Prints a short "APPLICATION FAILED TO START" report for a bad JWT secret
 * instead of a bean-creation stack trace. Registered in META-INF/spring.factories.
 */
public class InvalidJwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<InvalidJwtSecretException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidJwtSecretException cause) {
        return new FailureAnalysis(
            cause.getMessage(),
            "Set JWT_SECRET to a random value of at least " + JwtSecretValidator.MIN_SECRET_BYTES
                + " bytes (" + JwtSecretValidator.GENERATE_HINT + ") in your shell, IDE run"
                + " configuration, or the .env file in the backend's working directory.",
            cause);
    }
}
