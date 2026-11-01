package com.foodmap.auth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RefreshTokenHasher.
 *
 * Why these tests matter:
 * The hasher is the foundation of refresh token security. If it ever becomes
 * non-deterministic (e.g., uses a per-call salt), lookup by hash breaks and
 * every refresh call returns 401. If it produces the wrong format, the DB
 * column constraint fires and auth writes fail silently.
 */
class RefreshTokenHasherTest {

    private final RefreshTokenHasher hasher = new RefreshTokenHasher();

    @Test
    void hash_produces64CharLowercaseHex() {
        // SHA-256 always produces exactly 32 bytes → 64 hex chars
        String result = hasher.hash("any-raw-opaque-token");

        assertThat(result)
                .hasSize(64)
                .matches("[0-9a-f]+"); // lowercase hex only
    }

    @Test
    void hash_isDeterministic_sameInputSameOutput() {
        // Critical: the service hashes the incoming token and looks it up in the DB.
        // If the hash were random (like bcrypt per-call salt), lookup would always miss.
        String token = "my-opaque-refresh-token-abc123xyz";
        assertThat(hasher.hash(token)).isEqualTo(hasher.hash(token));
    }

    @Test
    void hash_differentInputs_produceDifferentOutputs() {
        // Basic collision resistance check
        assertThat(hasher.hash("token-a")).isNotEqualTo(hasher.hash("token-b"));
    }

    @Test
    void hash_outputDoesNotContainRawInput() {
        // Sanity check that we're actually hashing, not encoding
        String raw = "my-secret-refresh-token";
        assertThat(hasher.hash(raw)).doesNotContain(raw);
    }
}
