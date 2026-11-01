package com.foodmap.security;

import com.foodmap.config.properties.JwtProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production only: the placeholder from .env.example is long enough to pass
 * the length check, so reject it explicitly. Runs at startup.
 */
@Component
@Profile("prod")
public class ProdJwtSecretGuard {

    /** Must match the JWT_SECRET placeholder in .env.example. */
    static final String EXAMPLE_PLACEHOLDER = "replace-this-with-a-strong-random-secret-minimum-32-characters";

    public ProdJwtSecretGuard(JwtProperties jwtProperties) {
        check(jwtProperties.getSecret());
    }

    static void check(String secret) {
        if (EXAMPLE_PLACEHOLDER.equals(secret)) {
            throw new InvalidJwtSecretException(
                "app.jwt.secret is still the .env.example placeholder. " + JwtSecretValidator.GENERATE_HINT);
        }
    }
}
