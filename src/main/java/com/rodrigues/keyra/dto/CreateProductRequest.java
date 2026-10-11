package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank @Size(max = 100) String name,

        @NotBlank
        @Size(max = 64)
        @Pattern(
                regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "must be lowercase letters and digits separated by single hyphens")
        String slug,

        @Size(max = 500) String description) {}
