package com.rodrigues.keyra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A product-scoped API key. Only the prefix and a SHA-256 hash of the full key are stored. */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, unique = true, updatable = false, length = 8)
    private String prefix;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> scopes;

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Only ACTIVE or REVOKED is stored; EXPIRED is derived from {@code expiresAt}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ApiKeyStatus status;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApiKey() {}

    public ApiKey(
            Product product,
            String name,
            String prefix,
            String keyHash,
            List<String> scopes,
            int rateLimitPerMinute,
            Instant expiresAt,
            UUID createdBy,
            Instant createdAt) {
        this.product = product;
        this.name = name;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.scopes = scopes;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.expiresAt = expiresAt;
        this.status = ApiKeyStatus.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /**
     * Whether this key may authenticate for {@code productSlug}: the hash matches, it is not revoked or expired,
     * and its product is active.
     */
    public boolean accepts(String keyHash, String productSlug, Instant now) {
        return MessageDigest.isEqual(
                        this.keyHash.getBytes(StandardCharsets.UTF_8), keyHash.getBytes(StandardCharsets.UTF_8))
                && status == ApiKeyStatus.ACTIVE
                && (expiresAt == null || expiresAt.isAfter(now))
                && product.isActive()
                && product.getSlug().equals(productSlug);
    }

    public UUID getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ApiKeyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
