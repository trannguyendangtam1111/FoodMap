package com.foodmap.auth.service;

import com.foodmap.auth.domain.RefreshToken;
import com.foodmap.auth.dto.AuthResponse;
import com.foodmap.auth.dto.AuthUserDTO;
import com.foodmap.auth.dto.LoginRequest;
import com.foodmap.auth.dto.RefreshTokenRequest;
import com.foodmap.auth.dto.RegisterRequest;
import com.foodmap.auth.repository.RefreshTokenRepository;
import com.foodmap.auth.util.RefreshTokenHasher;
import com.foodmap.common.exception.BusinessException;
import com.foodmap.config.properties.JwtProperties;
import com.foodmap.security.JwtTokenProvider;
import com.foodmap.user.domain.Role;
import com.foodmap.user.domain.User;
import com.foodmap.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository           userRepository;
    private final RefreshTokenRepository   refreshTokenRepository;
    private final PasswordEncoder          passwordEncoder;
    private final JwtTokenProvider         jwtTokenProvider;
    private final AuthenticationManager    authenticationManager;
    private final JwtProperties            jwtProperties;
    private final RefreshTokenHasher       refreshTokenHasher;
    private final EntityManager            entityManager;

    /**
     * Register a new user with a hashed password and immediately issue tokens.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(
                "EMAIL_ALREADY_EXISTS",
                "An account with this email already exists.",
                HttpStatus.CONFLICT
            );
        }

        User user = User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .displayName(request.displayName())
            .role(Role.USER)
            .enabled(true)
            .build();

        User saved = userRepository.save(user);
        log.info("Registered new user: {} (id={})", saved.getEmail(), saved.getId());

        return issueTokens(saved);
    }

    /**
     * Authenticate credentials via Spring Security's AuthenticationManager.
     * BadCredentialsException is caught by GlobalExceptionHandler → 401.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BusinessException(
                "INVALID_CREDENTIALS",
                "Email or password is incorrect.",
                HttpStatus.UNAUTHORIZED
            ));

        return issueTokens(user);
    }

    /**
     * Exchange a refresh token for a new access + refresh token pair.
     *
     * SECURITY MODEL:
     *
     * 1. Hash the incoming raw token and look it up by hash.
     *    The DB never stores raw tokens — only SHA-256 hashes.
     *
     * 2. REUSE DETECTION: if the stored record is already revoked and someone
     *    presents the original token again, we assume either:
     *      a) A replay attack — attacker captured a used token and is replaying it.
     *      b) Token theft   — attacker got the token, used it first, and the
     *                         legitimate user is now presenting the same token.
     *    The correct response in both cases: revoke ALL sessions for this user
     *    immediately. This is the OAuth 2.1 §5.2.2.3 "refresh token reuse detection"
     *    pattern. The user is forced to log in again — a small disruption that stops
     *    the attacker cold.
     *
     * 3. OPTIMISTIC LOCKING: the @Version field on RefreshToken means that if two
     *    concurrent requests try to rotate the same token (network retry, two browser
     *    tabs), the second write will throw ObjectOptimisticLockingFailureException.
     *    We catch it and return 401. One token pair per rotation, enforced by the DB.
     *
     * noRollbackFor = BusinessException.class:
     *    BusinessException is a RuntimeException, so @Transactional would normally roll back
     *    when it propagates. But revokeAllForUser (step 2 above) MUST be committed even when
     *    we throw — otherwise the attacker's session remains active despite the security event.
     *    For the optimistic-locking path, we call entityManager.detach(stored) before re-throwing
     *    so Hibernate does not flush the dirty entity during commit.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawToken   = request.refreshToken();
        String tokenHash  = refreshTokenHasher.hash(rawToken);

        RefreshToken stored = refreshTokenRepository.findByToken(tokenHash)
            .orElseThrow(() -> new BusinessException(
                "INVALID_REFRESH_TOKEN",
                "Refresh token not recognized.",
                HttpStatus.UNAUTHORIZED
            ));

        // ── Reuse detection ───────────────────────────────────────────────────
        // A revoked token being presented again = replay attack or stolen token.
        // Revoke every active session for this user to contain the damage.
        if (stored.isRevoked()) {
            log.warn(
                "Refresh token reuse detected for user_id={}. Revoking all sessions.",
                stored.getUser().getId()
            );
            refreshTokenRepository.revokeAllForUser(stored.getUser().getId());
            throw new BusinessException(
                "INVALID_REFRESH_TOKEN",
                "Refresh token has been revoked.",
                HttpStatus.UNAUTHORIZED
            );
        }

        // ── Expiry check ──────────────────────────────────────────────────────
        if (stored.isExpired()) {
            throw new BusinessException(
                "INVALID_REFRESH_TOKEN",
                "Refresh token has expired.",
                HttpStatus.UNAUTHORIZED
            );
        }

        // ── Rotate: revoke old, issue new ─────────────────────────────────────
        // saveAndFlush forces Hibernate to issue the UPDATE immediately (before the
        // transaction commits). This is required for @Version to work as a guard:
        // the version check fires here, not at commit time, so we can catch it.
        stored.setRevoked(true);
        try {
            refreshTokenRepository.saveAndFlush(stored);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Two concurrent refresh requests raced on the same token.
            // The first one succeeded (new pair already issued to the other request).
            // Reject this one — it is now stale.
            //
            // Detach the entity before re-throwing: we are using noRollbackFor so the
            // transaction will commit, and without detach Hibernate would try to flush
            // stored's dirty state (revoked=true) again — causing a second version-check
            // failure at commit time. Detaching removes it from the persistence context.
            log.warn(
                "Concurrent refresh token rotation blocked for user_id={}",
                stored.getUser().getId()
            );
            entityManager.detach(stored);
            throw new BusinessException(
                "INVALID_REFRESH_TOKEN",
                "Refresh token is no longer valid.",
                HttpStatus.UNAUTHORIZED
            );
        }

        return issueTokens(stored.getUser());
    }

    /**
     * Revoke a refresh token on logout.
     *
     * The incoming token is hashed before the DB lookup — consistent with how tokens
     * are stored. Idempotent: silently succeeds even for unknown tokens to avoid
     * leaking information about token existence to a potential attacker.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        int updated = refreshTokenRepository.revokeByToken(tokenHash);
        if (updated == 0) {
            log.debug("Logout called with unrecognized refresh token hash (no-op)");
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Generate a new access + refresh token pair and persist the refresh token record.
     *
     * ACCESS TOKEN  — a short-lived (1h) JWT. Stateless: validated by signature only.
     *                 Never stored server-side.
     *
     * REFRESH TOKEN — a 32-byte SecureRandom opaque token (NOT a JWT).
     *                 The raw token is returned to the client.
     *                 Only the SHA-256 hash is stored in the database.
     *                 Valid for 7 days; single-use (rotated on every use).
     */
    private AuthResponse issueTokens(User user) {
        String accessToken    = jwtTokenProvider.generateAccessToken(user);
        String rawRefreshToken = jwtTokenProvider.generateOpaqueToken();
        String tokenHash      = refreshTokenHasher.hash(rawRefreshToken);

        Instant refreshExpiresAt = Instant.now()
            .plusMillis(jwtProperties.getRefreshTokenExpirationMs());

        refreshTokenRepository.save(RefreshToken.builder()
            .token(tokenHash)          // ← hash only, raw token never persisted
            .user(user)
            .expiresAt(refreshExpiresAt)
            .revoked(false)
            .build());

        return AuthResponse.of(
            accessToken,
            rawRefreshToken,           // ← raw token returned to client
            jwtProperties.getAccessTokenExpirationMs(),
            AuthUserDTO.from(user)
        );
    }
}
