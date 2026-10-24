package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UserKeySettingsRequest(
        @NotNull Boolean enabled,
        List<@NotBlank @Size(max = 100) String> defaultScopes,
        @NotNull @Min(1) Integer maxKeysPerUser) {

    public UserKeySettingsRequest {
        defaultScopes = defaultScopes == null ? List.of() : defaultScopes;
    }
}
