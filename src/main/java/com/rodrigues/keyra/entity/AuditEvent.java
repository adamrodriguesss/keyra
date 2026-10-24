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

/** Who did what to which object. Write-only for now: nothing reads these back through the API. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private AuditActorType actorType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private AuditTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    private AuditEvent(
            UUID actorId,
            AuditActorType actorType,
            AuditAction action,
            AuditTargetType targetType,
            UUID targetId,
            Instant createdAt) {
        this.actorId = actorId;
        this.actorType = actorType;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.createdAt = createdAt;
    }

    public static AuditEvent byManager(
            UUID managerId, AuditAction action, AuditTargetType targetType, UUID targetId, Instant at) {
        return new AuditEvent(managerId, AuditActorType.MANAGER, action, targetType, targetId, at);
    }

    public static AuditEvent byUser(
            UUID userId, AuditAction action, AuditTargetType targetType, UUID targetId, Instant at) {
        return new AuditEvent(userId, AuditActorType.USER, action, targetType, targetId, at);
    }

    /** Dated at the key's expiry itself, not at when it was noticed. */
    public static AuditEvent keyExpired(ApiKey key) {
        return new AuditEvent(
                null,
                AuditActorType.SYSTEM,
                AuditAction.EXPIRE,
                AuditTargetType.API_KEY,
                key.getId(),
                key.getExpiresAt());
    }

    public AuditAction getAction() {
        return action;
    }

    public UUID getActorId() {
        return actorId;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
