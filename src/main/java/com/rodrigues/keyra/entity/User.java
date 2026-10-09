package com.rodrigues.keyra.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User extends Account {

    protected User() {}

    public User(String email, String passwordHash, Instant createdAt) {
        super(email, passwordHash, createdAt);
    }
}
