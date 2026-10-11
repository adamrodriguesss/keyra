package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidateKeyRequest(
        @NotBlank String product, @NotBlank String key) {}
