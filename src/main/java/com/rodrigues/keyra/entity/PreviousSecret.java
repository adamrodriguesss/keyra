package com.rodrigues.keyra.entity;

import static com.rodrigues.keyra.security.TokenHashing.hashEquals;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

/** The secret before the last rotation, still accepted until {@code validUntil}. */
@Embeddable
public class PreviousSecret {

    @Column(name = "previous_key_hash", length = 64)
    private String hash;

    @Column(name = "previous_valid_until")
    private Instant validUntil;

    protected PreviousSecret() {}

    public PreviousSecret(String hash, Instant validUntil) {
        this.hash = hash;
        this.validUntil = validUntil;
    }

    boolean acceptsAt(String candidateHash, Instant now) {
        return validUntil.isAfter(now) && hashEquals(hash, candidateHash);
    }
}
