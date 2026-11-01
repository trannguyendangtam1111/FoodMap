package com.foodmap.auth.domain;

import com.foodmap.user.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Server-side record of issued refresh tokens.
 *
 * STORAGE MODEL:
 *   The {@code token} column stores the SHA-256 hex digest of the raw opaque token,
 *   never the raw token itself. The raw token (43-char base64url string) is returned
 *   to the client and never written to disk. If the database is compromised, the
 *   attacker gets unusable hashes.
 *
 * ROTATION:
 *   On each successful refresh, the old row is marked revoked and a new row is inserted.
 *   {@code @Version} provides optimistic locking: a concurrent second use of the same
 *   token will fail at the DB write, preventing double-spend.
 *
 * REUSE DETECTION:
 *   If a revoked token is presented, ALL tokens for that user are revoked immediately.
 *   This is the standard response to token replay / theft detection.
 *
 * Access tokens are not stored — they are stateless JWTs validated by signature only.
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_tokens_token",      columnList = "token", unique = true),
        @Index(name = "idx_refresh_tokens_user_id",    columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SHA-256 hex digest of the raw opaque token (never the raw token itself).
     * SHA-256 always produces exactly 64 hex characters → VARCHAR(64).
     */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /**
     * Optimistic lock version. Hibernate increments this on every UPDATE.
     * If two concurrent refresh requests try to revoke the same token row,
     * the second write fails with ObjectOptimisticLockingFailureException,
     * which the service catches and converts to 401. One rotation per token.
     */
    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return !revoked && !isExpired();
    }
}
