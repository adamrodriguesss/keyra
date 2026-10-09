package com.rodrigues.keyra.config;

import com.rodrigues.keyra.entity.SubjectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Keyra settings. Deployed instances set these through {@code KEYRA_*} env vars (see application.properties). */
@Validated
@ConfigurationProperties("keyra")
public record KeyraProperties(
        @NotBlank String issuerUrl,
        @Valid @DefaultValue Jwt jwt,
        @Valid @DefaultValue Bootstrap bootstrap,
        @Valid @DefaultValue Tokens tokens) {

    /**
     * @param privateKey PKCS#8 PEM RSA private key used to sign access tokens
     * @param keyId the {@code kid} published in the JWKS
     * @param ephemeralKey generate a throwaway key when none is configured. Local runs and tests only:
     *     tokens stop working on restart and are not shared between replicas.
     */
    public record Jwt(String privateKey, String keyId, boolean ephemeralKey) {}

    /** The first admin, created at startup when no managers exist yet. */
    public record Bootstrap(String adminEmail, String adminPassword) {}

    public record Tokens(
            @NotNull @DefaultValue("15m") Duration accessTokenTtl,
            @NotNull @DefaultValue("7d") Duration userRefreshTokenTtl,
            @NotNull @DefaultValue("24h") Duration managerRefreshTokenTtl) {

        public Duration refreshTokenTtl(SubjectType type) {
            return switch (type) {
                case USER -> userRefreshTokenTtl;
                case MANAGER -> managerRefreshTokenTtl;
            };
        }
    }
}
