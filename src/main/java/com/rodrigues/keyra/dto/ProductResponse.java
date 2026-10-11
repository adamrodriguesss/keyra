package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.Product;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record ProductResponse(
        UUID id,
        String name,
        String slug,
        String description,
        boolean active,
        Instant createdAt,
        Set<UUID> managerIds) {

    /** Reads the lazy manager set, so call it inside a transaction. */
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.isActive(),
                product.getCreatedAt(),
                product.getManagers().stream().map(Manager::getId).collect(Collectors.toSet()));
    }
}
