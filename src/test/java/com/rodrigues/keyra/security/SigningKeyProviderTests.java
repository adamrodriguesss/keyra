package com.rodrigues.keyra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rodrigues.keyra.config.KeyraProperties;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SigningKeyProviderTests {

    @Test
    void loadsAConfiguredPemKeyWithItsKeyId() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        var key = SigningKeyProvider.load(new KeyraProperties.Jwt(pem, "prod-2026", false));

        assertThat(key.getKeyID()).isEqualTo("prod-2026");
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.toPublicJWK().isPrivate()).isFalse();
    }

    @Test
    void refusesToStartWithoutAKeyUnlessEphemeralIsAllowed() {
        assertThatThrownBy(() -> SigningKeyProvider.load(new KeyraProperties.Jwt("", "", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KEYRA_JWT_PRIVATE_KEY");

        assertThat(SigningKeyProvider.load(new KeyraProperties.Jwt("", "", true))
                        .getKeyID())
                .startsWith("ephemeral-");
    }

    @Test
    void requiresAKeyIdWithAConfiguredKey() {
        assertThatThrownBy(() -> SigningKeyProvider.load(new KeyraProperties.Jwt("ignored", "", false)))
                .hasMessageContaining("KEYRA_JWT_KEY_ID");
    }

    @Test
    void rejectsAnInvalidPem() {
        assertThatThrownBy(() -> SigningKeyProvider.load(new KeyraProperties.Jwt("not a key", "k1", false)))
                .hasMessageContaining("not a valid");
    }
}
