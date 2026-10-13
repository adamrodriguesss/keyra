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

    public static final String API_KEY = "API_KEY";

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

    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    public AuditEvent(
            UUID actorId,
            AuditActorType actorType,
            AuditAction action,
            String targetType,
            UUID targetId,
            Instant createdAt) {
        this.actorId = actorId;
        this.actorType = actorType;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.createdAt = createdAt;
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
