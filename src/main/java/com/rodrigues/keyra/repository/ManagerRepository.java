package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.AccountStatus;
import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.ManagerRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.Lock;

public interface ManagerRepository extends AccountRepository<Manager> {

    List<Manager> findAllByOrderByCreatedAtAsc();

    /** Locks the rows, so concurrent changes that could remove the last active admin are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Manager> findByRoleAndStatus(ManagerRole role, AccountStatus status);
}
