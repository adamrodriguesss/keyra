package com.rodrigues.keyra.entity;

/** Who caused an audit event: a manager, a user, or Keyra itself (for example a key reaching its expiry). */
public enum AuditActorType {
    MANAGER,
    USER,
    SYSTEM
}
