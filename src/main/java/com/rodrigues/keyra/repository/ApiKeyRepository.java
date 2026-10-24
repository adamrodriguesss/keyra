package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.ApiKey;
import com.rodrigues.keyra.entity.ApiKeyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findAllByProduct_IdOrderByCreatedAtAsc(UUID productId);

    List<ApiKey> findAllByProduct_IdAndOwner_IdOrderByCreatedAtAsc(UUID productId, UUID ownerId);

    List<ApiKey> findAllByProduct_IdAndOwner_IdAndStatus(UUID productId, UUID ownerId, ApiKeyStatus status);

    Optional<ApiKey> findByIdAndProduct_Id(UUID id, UUID productId);

    Optional<ApiKey> findByIdAndProduct_IdAndOwner_Id(UUID id, UUID productId, UUID ownerId);

    @Query("""
            select count(k) from ApiKey k
            where k.product.id = :productId and k.owner.id = :ownerId
              and k.status = com.rodrigues.keyra.entity.ApiKeyStatus.ACTIVE
              and (k.expiresAt is null or k.expiresAt > :now)""")
    long countUnexpiredActive(UUID productId, UUID ownerId, Instant now);

    @EntityGraph(attributePaths = {"product", "owner"})
    Optional<ApiKey> findByPrefix(String prefix);

    /** Returns 1 only for the first caller, so concurrent requests can't audit the same expiry twice. */
    @Modifying
    @Query(
            value = "update api_keys set expiry_audited = true where id = :id and not expiry_audited",
            nativeQuery = true)
    int markExpiryAudited(UUID id);
}
