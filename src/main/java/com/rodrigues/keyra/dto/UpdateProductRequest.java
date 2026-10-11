package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: a null field is left unchanged. The slug is immutable, so it is not here. */
public record UpdateProductRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String name,

        @Size(max = 500) String description,
        Boolean active) {}
