package com.rodrigues.keyra.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A failed validation is exactly {@code {"valid":false}}: it never says why. */
public sealed interface ValidateKeyResponse permits ValidateKeyResponse.Valid, ValidateKeyResponse.Invalid {

    Invalid INVALID = new Invalid(false);

    /** {@code expiresAt} is {@code null} for keys that never expire, {@code userId} for manager-issued keys. */
    record Valid(
            UUID keyId, String product, List<String> scopes, int rateLimitPerMinute, Instant expiresAt, UUID userId)
            implements ValidateKeyResponse {

        @JsonProperty
        public boolean valid() {
            return true;
        }
    }

    record Invalid(boolean valid) implements ValidateKeyResponse {}
}
