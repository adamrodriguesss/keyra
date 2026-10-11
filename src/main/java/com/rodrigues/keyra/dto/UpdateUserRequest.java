package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRequest(@NotNull AccountStatus status) {}
