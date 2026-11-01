package com.foodmap.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodmap.auth.repository.RefreshTokenRepository;
import com.foodmap.config.properties.JwtProperties;
import com.foodmap.review.repository.ReviewRepository;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;
import com.foodmap.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the security filter chain rules.
 *
 * These tests verify that SecurityConfig's authorization rules are wired correctly:
 * - Public GET endpoints don't require a token
 * - Protected endpoints return 401 without a token
 * - Protected endpoints return 401 with an invalid/tampered token
 * - ADMIN-only endpoints return 403 for a regular USER
 * - Swagger is always accessible
 *
 * Why this matters: misconfigured security rules are silent — the app boots normally
 * and every functional test passes, while production endpoints are wide open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityRulesTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JwtProperties jwtProperties;

    private static final String USER_EMAIL  = "sectest-user@foodmap.dev";
    private static final String ADMIN_EMAIL = "sectest-admin@foodmap.dev";
    private static final String PASSWORD    = "SecurePass1!";

    @BeforeEach
    void setup() {
        refreshTokenRepository.deleteAll();
        reviewRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Public endpoints ──────────────────────────────────────────────────────

    @Test
    void publicGetRestaurants_withoutAuth_isNotBlockedBySecurityFilter() throws Exception {
        // GET /api/v1/restaurants/** is in PUBLIC_GET_PATTERNS — no token required.
        // We assert ONLY that the security layer did not block the request
        // (401/403). The viewport query uses PostGIS (ST_MakeEnvelope), which
        // H2 doesn't have, so on the test profile the controller may surface
        // a 500. That's not what this test is about — it's about the security
        // filter chain. A Testcontainers-Postgres test would cover the happy
        // path against the real DB.
        int status = mockMvc.perform(get("/api/v1/restaurants/viewport")
                        .param("swLat", "10.0").param("swLng", "106.0")
                        .param("neLat", "11.0").param("neLng", "107.0"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void publicGetFoods_withoutAuth_isNotBlockedBySecurityFilter() throws Exception {
        // Same rationale: foods/search uses immutable_unaccent (Postgres-only),
        // which fails on H2. The security filter must let the request through.
        int status = mockMvc.perform(get("/api/v1/foods/search").param("query", "pho"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void foodRestaurantsList_isPublic_butNothingElseUnderDecisionsFoods() throws Exception {
        // The FoodPage list is anonymous (one path segment for the food key)…
        int list = mockMvc.perform(get("/api/v1/decisions/foods/xyz/restaurants"))
                .andReturn().getResponse().getStatus();
        assertThat(list).isEqualTo(404);   // reached the controller: an unknown food, not 401
        // …and the pattern opens nothing else: deeper or sibling paths, or other methods.
        for (String path : new String[]{"/api/v1/decisions/foods/pho/restaurants/1",
                                        "/api/v1/decisions/foods/pho/other",
                                        "/api/v1/decisions/foods/a/b/restaurants",
                                        "/api/v1/decisions/foods/pho"}) {
            assertThat(mockMvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
        assertThat(mockMvc.perform(post("/api/v1/decisions/foods/pho/restaurants"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void swaggerUi_withoutAuth_isAccessible() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    // ── Unauthenticated access to protected endpoints ─────────────────────────

    @Test
    void postReview_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/1/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5,"comment":"Great food!"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteReview_withoutToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/restaurants/1/reviews/1"))
                .andExpect(status().isUnauthorized());
    }

    // ── Invalid tokens ────────────────────────────────────────────────────────

    @Test
    void postReview_withInvalidJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants/1/reviews")
                        .header("Authorization", "Bearer this.is.not.a.valid.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postReview_withTamperedJwt_returns401() throws Exception {
        String validToken = registerAndGetToken(USER_EMAIL);
        // Corrupt the signature segment — correct format but invalid HMAC
        int lastDot = validToken.lastIndexOf('.');
        String tampered = validToken.substring(0, lastDot + 1) + "TAMPEREDPADDING";

        mockMvc.perform(post("/api/v1/restaurants/1/reviews")
                        .header("Authorization", "Bearer " + tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postReview_withExpiredJwt_returns401() throws Exception {
        // Build an already-expired token using a provider with -1ms expiry
        User user = userRepository.save(
                User.builder()
                        .email("expired@test.dev")
                        .passwordHash(passwordEncoder.encode(PASSWORD))
                        .displayName("Expired User")
                        .role(Role.USER)
                        .enabled(true)
                        .build()
        );

        JwtProperties shortLived = new JwtProperties();
        shortLived.setSecret(jwtProperties.getSecret());
        shortLived.setAccessTokenExpirationMs(-1000L); // born expired
        shortLived.setIssuer(jwtProperties.getIssuer());
        String expiredToken = new JwtTokenProvider(shortLived).generateAccessToken(user);

        mockMvc.perform(post("/api/v1/restaurants/1/reviews")
                        .header("Authorization", "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rating":5}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── Role-based access control ─────────────────────────────────────────────

    @Test
    void adminSync_withUserRole_returns403() throws Exception {
        // POST /api/v1/restaurants/*/sync requires ROLE_ADMIN
        // A regular USER must get 403, not 401 (they are authenticated, just forbidden)
        String userToken = registerAndGetToken(USER_EMAIL);

        mockMvc.perform(post("/api/v1/restaurants/1/sync")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSync_withAdminRole_isNotBlockedBySecurityFilter() throws Exception {
        // Admin user must pass the security layer (may still get 404 if restaurant doesn't exist,
        // but must NOT get 403 or 401)
        String adminToken = createAdminAndGetToken();

        int status = mockMvc.perform(post("/api/v1/restaurants/999/sync")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();

        // 403/401 = security blocked; 404/5xx = passed security, hit business logic
        assertThat(status).isNotIn(401, 403);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Test User"}
                                """.formatted(email, PASSWORD)))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.at("/data/accessToken").asText();
    }

    private String createAdminAndGetToken() {
        User admin = userRepository.save(
                User.builder()
                        .email(ADMIN_EMAIL)
                        .passwordHash(passwordEncoder.encode(PASSWORD))
                        .displayName("Admin User")
                        .role(Role.ADMIN)
                        .enabled(true)
                        .build()
        );
        return jwtTokenProvider.generateAccessToken(admin);
    }
}
