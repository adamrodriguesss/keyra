package com.rodrigues.keyra.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.rodrigues.keyra.config.KeyraProperties;
import com.rodrigues.keyra.entity.SubjectType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Issues RS256 access tokens. The audience separates user tokens from manager tokens. */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final String keyId;
    private final String issuer;
    private final Duration lifetime;
    private final Clock clock;

    public AccessTokenIssuer(SigningKeyProvider keys, KeyraProperties properties, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
        this.keyId = keys.signingKey().getKeyID();
        this.issuer = properties.issuerUrl();
        this.lifetime = properties.tokens().accessTokenTtl();
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID subjectId, SubjectType type, Map<String, Object> extraClaims) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subjectId.toString())
                .audience(List.of(type.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .id(UUID.randomUUID().toString());
        extraClaims.forEach(claims::claim);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
        return new IssuedAccessToken(token, lifetime);
    }

    public record IssuedAccessToken(String value, Duration lifetime) {}
}
