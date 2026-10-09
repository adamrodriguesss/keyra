package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.AccountStatus;
import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.ManagerRole;
import java.time.Instant;
import java.util.UUID;

public record ManagerResponse(UUID id, String email, ManagerRole role, AccountStatus status, Instant createdAt) {

    public static ManagerResponse from(Manager manager) {
        return new ManagerResponse(
                manager.getId(), manager.getEmail(), manager.getRole(), manager.getStatus(), manager.getCreatedAt());
    }
}
