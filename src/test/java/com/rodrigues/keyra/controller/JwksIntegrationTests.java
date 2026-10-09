package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.rodrigues.keyra.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Verifies a user token the way a product would: with only the public JWKS. */
@IntegrationTest
class JwksIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void userAccessTokenVerifiesAgainstPublishedJwks() throws Exception {
        var jwksResult = mvc.get().uri("/.well-known/jwks.json").exchange();
        assertThat(jwksResult).hasStatusOk();
        JWKSet jwks = JWKSet.parse(jwksResult.getResponse().getContentAsString());
        assertThat(jwks.getKeys()).hasSize(1);
        RSAKey key = jwks.getKeys().getFirst().toRSAKey();
        assertThat(key.isPrivate()).isFalse();

        var tokens = new AuthApi(mvc).registerAndLogin();
        SignedJWT jwt = SignedJWT.parse(tokens.accessToken());

        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(key.getKeyID());
        assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:8080");
        assertThat(claims.getAudience()).containsExactly("keyra-user");
        assertThat(claims.getSubject()).isNotBlank();
        assertThat(claims.getExpirationTime().toInstant()).isAfter(Instant.now());
        assertThat(claims.getIssueTime()).isNotNull();
    }
}
