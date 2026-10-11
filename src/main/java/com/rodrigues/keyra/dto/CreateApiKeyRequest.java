package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Defaults: no scopes, 90 days, 60 requests per minute. {@code neverExpires} is admin-only. */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 100) String name,
        List<@NotBlank @Size(max = 100) String> scopes,
        @Min(1) @Max(365) Integer expiresInDays,
        Boolean neverExpires,
        @Min(1) Integer rateLimitPerMinute) {

    public CreateApiKeyRequest {
        neverExpires = Boolean.TRUE.equals(neverExpires);
        scopes = scopes == null ? List.of() : scopes;
        expiresInDays = expiresInDays == null && !neverExpires ? Integer.valueOf(90) : expiresInDays;
        rateLimitPerMinute = rateLimitPerMinute == null ? Integer.valueOf(60) : rateLimitPerMinute;
    }

    @AssertTrue(message = "neverExpires and expiresInDays conflict.")
    public boolean isExpiryConsistent() {
        return !neverExpires || expiresInDays == null;
    }
}
