package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByTargetId(UUID targetId);
}
