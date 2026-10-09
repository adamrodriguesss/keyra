package com.rodrigues.keyra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Fields and rules shared by users and managers, which live in separate tables. */
@MappedSuperclass
public abstract class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {}

    protected Account(String email, String passwordHash, Instant createdAt) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    /** Emails are unique per instance and stored lowercased. Apply before every lookup by email. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
