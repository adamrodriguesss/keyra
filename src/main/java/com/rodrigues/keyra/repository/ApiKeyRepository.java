package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.ApiKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findAllByProduct_IdOrderByCreatedAtAsc(UUID productId);

    Optional<ApiKey> findByIdAndProduct_Id(UUID id, UUID productId);

    @EntityGraph(attributePaths = "product")
    Optional<ApiKey> findByPrefix(String prefix);

    /** Returns 1 only for the first caller, so concurrent requests can't audit the same expiry twice. */
    @Modifying
    @Query(
            value = "update api_keys set expiry_audited = true where id = :id and not expiry_audited",
            nativeQuery = true)
    int markExpiryAudited(UUID id);
}
