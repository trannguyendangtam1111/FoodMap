package com.foodmap.auth.util;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces a deterministic SHA-256 hex digest of a raw refresh token.
 *
 * WHY SHA-256, NOT BCRYPT:
 *   BCrypt is intentionally slow to resist brute-forcing passwords, which are
 *   low-entropy strings (e.g., "password123"). Our refresh tokens are 32 bytes
 *   of SecureRandom — 256 bits of entropy — making brute-force mathematically
 *   infeasible regardless of hash speed. Using BCrypt here would add 100–300ms
 *   latency to every refresh call for zero security benefit.
 *
 * WHY HASH AT ALL:
 *   If the database is compromised (SQL injection, backup leak, insider threat),
 *   the attacker gets hashes — not usable tokens. SHA-256 is one-way: you cannot
 *   reverse a hash back to the raw token. The raw token is never written to disk.
 *
 * SHA-256 output is always exactly 64 hex characters.
 */
@Component
public class RefreshTokenHasher {

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE specification — this cannot happen.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
