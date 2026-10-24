package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Partial update: a null field is left unchanged. */
public record UpdateApiKeyRequest(
        List<@NotBlank @Size(max = 100) String> scopes,
        @Min(1) Integer rateLimitPerMinute) {}
