package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.RefreshToken;
import com.rodrigues.keyra.entity.SubjectType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Locks the row so concurrent refreshes with the same token are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.subjectId = :subjectId and t.subjectType = :type and t.revokedAt is null""")
    int revokeAll(@Param("subjectId") UUID subjectId, @Param("type") SubjectType type, @Param("now") Instant now);
}
