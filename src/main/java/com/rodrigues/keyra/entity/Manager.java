package com.rodrigues.keyra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "managers")
public class Manager extends Account {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ManagerRole role;

    protected Manager() {}

    public Manager(String email, String passwordHash, ManagerRole role, Instant createdAt) {
        super(email, passwordHash, createdAt);
        this.role = role;
    }

    public ManagerRole getRole() {
        return role;
    }
}
