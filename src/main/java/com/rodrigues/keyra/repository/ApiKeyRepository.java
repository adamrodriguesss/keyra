package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.ApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findAllByProduct_IdOrderByCreatedAtAsc(UUID productId);

    @EntityGraph(attributePaths = "product")
    Optional<ApiKey> findByPrefix(String prefix);
}
