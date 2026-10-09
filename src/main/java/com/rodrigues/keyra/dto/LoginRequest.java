package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String password) {}
