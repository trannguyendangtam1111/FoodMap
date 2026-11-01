package com.foodmap.security;

import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Turns {@code app.jwt.secret} into the HS256 signing key, refusing weak values.
 *
 * The secret is interpreted exactly as before: its raw UTF-8 bytes are the key
 * (no Base64 decoding). HS256 needs at least 256 bits, so the check counts
 * UTF-8 bytes — not characters — which is what JJWT itself measures.
 *
 * Messages never contain the secret, only its byte length.
 */
public final class JwtSecretValidator {

    /** HS256 requires a key of at least 256 bits (RFC 7518 §3.2). */
    public static final int MIN_SECRET_BYTES = 32;

    static final String GENERATE_HINT = "Generate one with: openssl rand -base64 48";

    private JwtSecretValidator() {}

    /** Byte length JJWT will see for this secret. */
    public static int byteLength(String secret) {
        return secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Validates the secret and builds the signing key.
     *
     * @throws InvalidJwtSecretException when the secret is missing or shorter than 32 bytes
     */
    public static SecretKey toSigningKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new InvalidJwtSecretException(
                "app.jwt.secret is not set. Set JWT_SECRET to at least " + MIN_SECRET_BYTES
                    + " bytes. " + GENERATE_HINT);
        }
        int bytes = byteLength(secret);
        if (bytes < MIN_SECRET_BYTES) {
            throw new InvalidJwtSecretException(
                "app.jwt.secret is too short: " + bytes + " bytes (minimum " + MIN_SECRET_BYTES
                    + " bytes / 256 bits for HS256; the value is used as raw UTF-8 bytes). "
                    + GENERATE_HINT);
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
