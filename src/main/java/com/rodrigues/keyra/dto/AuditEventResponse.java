package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.AuditAction;
import com.rodrigues.keyra.entity.AuditActorType;
import java.time.Instant;
import java.util.UUID;

/** {@code actorEmail} is null for SYSTEM events and for an actor that has since been deleted. */
public record AuditEventResponse(
        UUID id,
        AuditAction action,
        AuditActorType actorType,
        UUID actorId,
        String actorEmail,
        UUID keyId,
        String keyName,
        String keyPrefix,
        Instant createdAt) {}
