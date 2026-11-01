package com.foodmap.security;

import com.foodmap.config.properties.JwtProperties;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JwtTokenProvider.
 *
 * Why not use @SpringBootTest here:
 * JwtTokenProvider has zero external dependencies (only JwtProperties + JJWT).
 * Starting a full Spring context for a self-contained component is waste —
 * this runs in ~50ms vs ~3s for a slice test.
 *
 * Why these tests matter:
 * The JWT provider is the gatekeeper for every authenticated request.
 * A bug in expiry checking, signature validation, or subject extraction
 * would allow either auth bypass or spurious 401s on valid tokens.
 */
class JwtTokenProviderTest {

    private static final String TEST_SECRET =
            "test-secret-key-for-unit-tests-only-minimum-32-characters";

    private JwtTokenProvider tokenProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(TEST_SECRET);
        props.setAccessTokenExpirationMs(3_600_000L); // 1 hour
        props.setIssuer("foodmap-test");

        tokenProvider = new JwtTokenProvider(props);

        testUser = User.builder()
                .id(1L)
                .email("user@foodmap.dev")
                .passwordHash("$2a$12$irrelevant")
                .displayName("Test User")
                .role(Role.USER)
                .enabled(true)
                .build();
    }

    // ── Token generation ──────────────────────────────────────────────────────

    @Test
    void generateAccessToken_producesThreePartJwt() {
        String token = tokenProvider.generateAccessToken(testUser);
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void generateAccessToken_subjectIsUserEmail() {
        String token = tokenProvider.generateAccessToken(testUser);
        assertThat(tokenProvider.extractUsername(token)).isEqualTo("user@foodmap.dev");
    }

    @Test
    void generateOpaqueToken_is43CharBase64urlString() {
        // 32 bytes of SecureRandom → base64url without padding = ceil(32 * 4/3) = 43 chars
        // Base64url alphabet: A-Z, a-z, 0-9, -, _ (no +, /, or = padding)
        String token = tokenProvider.generateOpaqueToken();
        assertThat(token)
                .hasSize(43)
                .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generateOpaqueToken_eachCallProducesUniqueValue() {
        String t1 = tokenProvider.generateOpaqueToken();
        String t2 = tokenProvider.generateOpaqueToken();
        assertThat(t1).isNotEqualTo(t2);
    }

    // ── Token validation ──────────────────────────────────────────────────────

    @Test
    void isTokenValid_trueForFreshTokenAndMatchingUser() {
        String token = tokenProvider.generateAccessToken(testUser);
        assertThat(tokenProvider.isTokenValid(token, testUser)).isTrue();
    }

    @Test
    void isTokenValid_falseWhenUserEmailDoesNotMatchSubject() {
        String token = tokenProvider.generateAccessToken(testUser);

        User anotherUser = User.builder()
                .email("other@foodmap.dev")
                .passwordHash("$2a$12$irrelevant")
                .displayName("Other")
                .role(Role.USER)
                .enabled(true)
                .build();

        assertThat(tokenProvider.isTokenValid(token, anotherUser)).isFalse();
    }

    @Test
    void validateToken_falseForTamperedSignature() {
        String token = tokenProvider.generateAccessToken(testUser);
        // Corrupt the signature segment (third part of the JWT)
        int lastDot = token.lastIndexOf('.');
        String tampered = token.substring(0, lastDot + 1) + "INVALIDSIGNATURE";

        assertThat(tokenProvider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_falseForRandomGarbage() {
        assertThat(tokenProvider.validateToken("not.a.jwt")).isFalse();
        assertThat(tokenProvider.validateToken("eyJhbGciOiJIUzI1NiJ9.garbage.sig")).isFalse();
    }

    @Test
    void validateToken_falseForExpiredToken() {
        // Build a provider with -1s expiration so the token is born already expired
        JwtProperties shortLivedProps = new JwtProperties();
        shortLivedProps.setSecret(TEST_SECRET);
        shortLivedProps.setAccessTokenExpirationMs(-1000L);
        shortLivedProps.setIssuer("foodmap-test");
        JwtTokenProvider expiredProvider = new JwtTokenProvider(shortLivedProps);

        String expiredToken = expiredProvider.generateAccessToken(testUser);

        // validateToken catches ExpiredJwtException (subclass of JwtException) → false
        assertThat(expiredProvider.validateToken(expiredToken)).isFalse();
    }
}
