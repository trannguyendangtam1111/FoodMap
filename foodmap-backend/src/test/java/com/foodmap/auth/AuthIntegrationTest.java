package com.foodmap.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.auth.repository.RefreshTokenRepository;
import com.foodmap.review.repository.ReviewRepository;
import com.foodmap.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the complete authentication flow.
 *
 * Strategy: go through the real HTTP stack (filter chain → controller → service → DB).
 * This validates that security config, JWT generation, token hashing, and DB writes all
 * work together end-to-end. No mocks — every failure points to a real bug.
 *
 * The most important test here is the refresh-token reuse detection test, which verifies
 * the OAuth 2.1 §5.2.2.3 token replay / theft response: all sessions revoked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired ReviewRepository reviewRepository;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        reviewRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void register_validData_returns201WithTokenPair() throws Exception {
        // Success responses are wrapped: ApiResponse { success: true, data: AuthResponse }
        // AuthResponse.user (AuthUserDTO) intentionally includes the email so the client
        // knows which account was created — this is not a security leak.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new@test.dev","password":"SecurePass1!","displayName":"Test User"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.id").isNumber())
                .andExpect(jsonPath("$.data.user.displayName").value("Test User"));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        String body = """
                {"email":"dup@test.dev","password":"SecurePass1!","displayName":"First"}
                """;
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Error responses from GlobalExceptionHandler are plain ErrorResponse objects,
        // not wrapped in ApiResponse. The response body is { status, error, message, path }.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void register_invalidEmail_returns400WithFieldError() throws Exception {
        // Validation errors use ErrorResponse.ofValidation() which adds a fieldErrors map.
        // Response shape: { status, error, message, path, fieldErrors: { email: "..." } }
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"SecurePass1!","displayName":"User"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@test.dev","password":"short","displayName":"User"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void register_blankDisplayName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@test.dev","password":"SecurePass1!","displayName":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.displayName").exists());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithTokenPair() throws Exception {
        register("login@test.dev", "SecurePass1!", "Login User");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"login@test.dev","password":"SecurePass1!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        register("wrongpw@test.dev", "SecurePass1!", "User");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"wrongpw@test.dev","password":"WrongPassword!"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_unknownEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ghost@test.dev","password":"SecurePass1!"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returnsNewTokenPair() throws Exception {
        AuthTokens tokens = register("refresh@test.dev", "SecurePass1!", "User");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    void refresh_usedToken_returns401AndRevokesAllSessions() throws Exception {
        // Simulates the token reuse / replay attack scenario:
        // The legitimate user has already refreshed (rotating the old token).
        // An attacker who captured the old token tries to use it.
        // Expected: all sessions nuked, user must re-authenticate.
        AuthTokens tokens = register("reuse@test.dev", "SecurePass1!", "User");

        // Legitimate first use — rotates the token
        MvcResult firstRefresh = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andReturn();

        String newRefreshToken = objectMapper.readTree(
                firstRefresh.getResponse().getContentAsString())
                .at("/data/refreshToken").asText();

        // Attacker replays the original (now revoked) token
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());

        // The newly issued token from the first refresh must ALSO be invalidated.
        // Reuse detection revokes ALL sessions for the user, not just the replayed one.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(newRefreshToken)))
                .andExpect(status().isUnauthorized());

        // Verify in the DB: no active tokens remain for this user
        long activeCount = refreshTokenRepository.findAll().stream()
                .filter(rt -> !rt.isRevoked())
                .count();
        assertThat(activeCount).isZero();
    }

    @Test
    void refresh_unknownToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"completelyfaketokenthatdoesnotexistindb1234"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_thenRefreshWithSameToken_returns401() throws Exception {
        AuthTokens tokens = register("logout@test.dev", "SecurePass1!", "User");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isOk());

        // Refresh after logout must fail — token is revoked
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withUnknownToken_isIdempotent() throws Exception {
        // Should succeed silently — no information leakage about token existence
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"unknowntokenthatdoesnotexist1234567890123"}
                                """))
                .andExpect(status().isOk());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthTokens register(String email, String password, String displayName) throws Exception {
        String body = """
                {"email":"%s","password":"%s","displayName":"%s"}
                """.formatted(email, password, displayName);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new AuthTokens(
                json.at("/data/accessToken").asText(),
                json.at("/data/refreshToken").asText()
        );
    }

    record AuthTokens(String accessToken, String refreshToken) {}
}
