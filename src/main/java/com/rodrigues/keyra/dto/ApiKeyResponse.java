package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.ApiKey;
import com.rodrigues.keyra.entity.ApiKeyStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        UUID ownerUserId,
        String name,
        String prefix,
        List<String> scopes,
        int rateLimitPerMinute,
        Instant expiresAt,
        ApiKeyStatus status,
        Instant createdAt) {

    /** {@code status} is the effective one (see {@code ApiKey#statusAt}), which may be EXPIRED though that is never stored. */
    public static ApiKeyResponse from(ApiKey key, ApiKeyStatus status) {
        return new ApiKeyResponse(
                key.getId(),
                key.getOwnerId(),
                key.getName(),
                key.getPrefix(),
                key.getScopes(),
                key.getRateLimitPerMinute(),
                key.getExpiresAt(),
                status,
                key.getCreatedAt());
    }
}
