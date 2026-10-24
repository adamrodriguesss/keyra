package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Like {@link CreateApiKeyRequest} without {@code neverExpires}. Null {@code scopes} means the product's defaults. */
public record CreateUserApiKeyRequest(
        @NotBlank @Size(max = 100) String name,
        List<@NotBlank @Size(max = 100) String> scopes,
        @Min(1) @Max(365) Integer expiresInDays,
        @Min(1) Integer rateLimitPerMinute) {

    public CreateUserApiKeyRequest {
        expiresInDays = expiresInDays == null ? Integer.valueOf(90) : expiresInDays;
        rateLimitPerMinute = rateLimitPerMinute == null ? Integer.valueOf(60) : rateLimitPerMinute;
    }
}
