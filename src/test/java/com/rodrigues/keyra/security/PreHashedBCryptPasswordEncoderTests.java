package com.rodrigues.keyra.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreHashedBCryptPasswordEncoderTests {

    private final PreHashedBCryptPasswordEncoder encoder = new PreHashedBCryptPasswordEncoder(4);

    @Test
    void matchesTheOriginalPassword() {
        String hash = encoder.encode("correct-horse-battery");

        assertThat(hash).startsWith("$2a$04$");
        assertThat(encoder.matches("correct-horse-battery", hash)).isTrue();
        assertThat(encoder.matches("correct-horse-batterx", hash)).isFalse();
    }

    @Test
    void charactersBeyondBcryptsSeventyTwoByteLimitStillCount() {
        String prefix = "x".repeat(100);
        String hash = encoder.encode(prefix + "1");

        assertThat(encoder.matches(prefix + "1", hash)).isTrue();
        assertThat(encoder.matches(prefix + "2", hash)).isFalse();
    }

    @Test
    void productionCostIsTwelve() {
        assertThat(new PreHashedBCryptPasswordEncoder().encode("whatever-password"))
                .startsWith("$2a$12$");
    }
}
