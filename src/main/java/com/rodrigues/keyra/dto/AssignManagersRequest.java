package com.rodrigues.keyra.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;

/** The complete set of managers assigned to the product. An empty set clears it. */
public record AssignManagersRequest(@NotNull Set<UUID> managerIds) {}
