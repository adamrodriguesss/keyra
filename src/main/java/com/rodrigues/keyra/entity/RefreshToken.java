package com.rodrigues.keyra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A refresh token. Only the SHA-256 hash of the token is stored. */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private SubjectType subjectType;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    public RefreshToken(
            UUID subjectId, SubjectType subjectType, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.subjectId = subjectId;
        this.subjectType = subjectType;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    /**
     * Uses the token up for a rotation: revokes it if it is active, unexpired, and issued to {@code type}.
     * Returns whether it was usable. Callers must hold a row lock so two refreshes can't both succeed.
     */
    public boolean consume(SubjectType type, Instant now) {
        boolean usable = subjectType == type && revokedAt == null && expiresAt.isAfter(now);
        if (usable) {
            revokedAt = now;
        }
        return usable;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
