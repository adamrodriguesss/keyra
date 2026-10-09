package com.rodrigues.keyra.service;

import com.rodrigues.keyra.config.KeyraProperties;
import com.rodrigues.keyra.entity.RefreshToken;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.exception.InvalidRefreshTokenException;
import com.rodrigues.keyra.repository.RefreshTokenRepository;
import com.rodrigues.keyra.security.TokenHashing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opaque refresh tokens. The raw token is returned once; only its SHA-256 hash is stored. */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final KeyraProperties.Tokens tokens;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, KeyraProperties properties, Clock clock) {
        this.repository = repository;
        this.tokens = properties.tokens();
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issue(UUID subjectId, SubjectType type) {
        String raw = TokenHashing.randomToken(TOKEN_BYTES);
        Duration lifetime = tokens.refreshTokenTtl(type);
        Instant now = clock.instant();
        repository.save(new RefreshToken(subjectId, type, TokenHashing.sha256Hex(raw), now.plus(lifetime), now));
        return new IssuedRefreshToken(raw, lifetime);
    }

    /**
     * Revokes the token and returns its subject, so the caller can issue a new pair. Fails if the token is
     * unknown, expired, revoked, or belongs to the other realm.
     */
    @Transactional
    public UUID consume(String rawToken, SubjectType type) {
        return repository
                .findByTokenHash(TokenHashing.sha256Hex(rawToken))
                .filter(token -> token.consume(type, clock.instant()))
                .map(RefreshToken::getSubjectId)
                .orElseThrow(InvalidRefreshTokenException::new);
    }

    /** Revokes the token if it belongs to this realm. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String rawToken, SubjectType type) {
        repository
                .findByTokenHash(TokenHashing.sha256Hex(rawToken))
                .filter(token -> token.getSubjectType() == type)
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    public record IssuedRefreshToken(String value, Duration lifetime) {}
}
