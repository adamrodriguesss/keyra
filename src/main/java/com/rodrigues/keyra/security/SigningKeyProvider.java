package com.rodrigues.keyra.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.rodrigues.keyra.config.KeyraProperties;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Holds the RSA key that signs access tokens, loaded from config or generated for local use. */
@Component
public class SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyProvider.class);

    private final RSAKey signingKey;

    public SigningKeyProvider(KeyraProperties properties) {
        this.signingKey = load(properties.jwt());
    }

    /** The private signing key. Never expose this outside the token issuer. */
    public RSAKey signingKey() {
        return signingKey;
    }

    public RSAPublicKey publicKey() {
        try {
            return signingKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("Signing key has no public part", e);
        }
    }

    /** The public JWK set served at {@code /.well-known/jwks.json}. */
    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey.toPublicJWK());
    }

    static RSAKey load(KeyraProperties.Jwt jwt) {
        boolean hasKey = jwt.privateKey() != null && !jwt.privateKey().isBlank();
        if (hasKey) {
            if (jwt.keyId() == null || jwt.keyId().isBlank()) {
                throw new IllegalStateException("KEYRA_JWT_KEY_ID must be set when KEYRA_JWT_PRIVATE_KEY is set");
            }
            return fromPem(jwt.privateKey(), jwt.keyId());
        }
        if (!jwt.ephemeralKey()) {
            throw new IllegalStateException("KEYRA_JWT_PRIVATE_KEY is not set. Configure an RSA signing key "
                    + "(or keyra.jwt.ephemeral-key=true for local development only).");
        }
        log.warn("Using an ephemeral JWT signing key. Tokens will not survive a restart or work across replicas.");
        return generate();
    }

    static RSAKey fromPem(String pem, String keyId) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            var publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return build(publicKey, privateKey, keyId);
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("KEYRA_JWT_PRIVATE_KEY is not a valid PKCS#8 PEM RSA private key", e);
        }
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return build(
                    (RSAPublicKey) pair.getPublic(),
                    (RSAPrivateCrtKey) pair.getPrivate(),
                    "ephemeral-" + UUID.randomUUID());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an RSA key", e);
        }
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey, String keyId) {
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }
}
