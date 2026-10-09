package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface AccountRepository<A extends Account> extends JpaRepository<A, UUID> {

    /** Expects an email already passed through {@link Account#normalizeEmail}. */
    Optional<A> findByEmail(String email);
}
