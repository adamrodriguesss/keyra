package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.AccountStatus;
import com.rodrigues.keyra.entity.User;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, AccountStatus status, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getStatus(), user.getCreatedAt());
    }
}
