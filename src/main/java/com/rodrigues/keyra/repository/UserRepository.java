package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserRepository extends AccountRepository<User> {

    /** Expects an email already passed through {@link com.rodrigues.keyra.entity.Account#normalizeEmail}. */
    Page<User> findByEmailContaining(String email, Pageable pageable);
}
