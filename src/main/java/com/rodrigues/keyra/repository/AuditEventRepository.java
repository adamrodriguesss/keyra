package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByTargetId(UUID targetId);

    record KeyEvent(AuditEvent event, String keyName, String keyPrefix) {}

    /** Events of a product's keys, newest first (the sort is in the query, so pass an unsorted page request). */
    @Query(value = """
            select new com.rodrigues.keyra.repository.AuditEventRepository$KeyEvent(e, k.name, k.prefix)
            from AuditEvent e join ApiKey k on k.id = e.targetId
            where k.product.id = :productId and (:keyId is null or k.id = :keyId)
            order by e.createdAt desc, e.id desc""", countQuery = """
            select count(e) from AuditEvent e join ApiKey k on k.id = e.targetId
            where k.product.id = :productId and (:keyId is null or k.id = :keyId)""")
    Page<KeyEvent> findByProduct(@Param("productId") UUID productId, @Param("keyId") UUID keyId, Pageable pageable);
}
