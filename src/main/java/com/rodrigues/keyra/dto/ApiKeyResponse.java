package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.ApiKey;
import com.rodrigues.keyra.entity.ApiKeyStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code status} reports EXPIRED once an active key passes its expiry; that is never stored. */
public record ApiKeyResponse(
        UUID id,
        String name,
        String prefix,
        List<String> scopes,
        int rateLimitPerMinute,
        Instant expiresAt,
        ApiKeyStatus status,
        Instant createdAt) {

    public static ApiKeyResponse from(ApiKey key, Instant now) {
        return new ApiKeyResponse(
                key.getId(),
                key.getName(),
                key.getPrefix(),
                key.getScopes(),
                key.getRateLimitPerMinute(),
                key.getExpiresAt(),
                key.isExpired(now) ? ApiKeyStatus.EXPIRED : key.getStatus(),
                key.getCreatedAt());
    }
}
