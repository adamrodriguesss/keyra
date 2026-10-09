package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.LoginRequest;
import com.rodrigues.keyra.dto.TokenResponse;
import com.rodrigues.keyra.entity.Account;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.exception.InvalidCredentialsException;
import com.rodrigues.keyra.exception.InvalidRefreshTokenException;
import com.rodrigues.keyra.exception.NotFoundException;
import com.rodrigues.keyra.repository.AccountRepository;
import com.rodrigues.keyra.security.AccessTokenIssuer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

/**
 * An account realm: users or managers. Authenticates the realm's accounts and issues their sessions (an
 * access token plus a rotating refresh token). A subclass only says what is different about its realm:
 * which claims its tokens carry and the authorities those claims grant.
 */
public abstract class Realm<A extends Account> {

    /**
     * BCrypt (cost 12) hash of a random value nobody knows. Checked when the email is unknown, so the
     * response time doesn't reveal which emails exist. Must use the same cost as {@code
     * PreHashedBCryptPasswordEncoder}.
     */
    private static final String UNKNOWN_ACCOUNT_HASH = "$2a$12$ugLJIWELDaaVq1Z15znlQu1HZ5B9w0RasQ2qzHIW2S544qZ6H9ufS";

    private final SubjectType type;
    private final AccountRepository<A> accounts;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;

    protected Realm(
            SubjectType type,
            AccountRepository<A> accounts,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokens,
            RefreshTokenService refreshTokens) {
        this.type = type;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    public SubjectType type() {
        return type;
    }

    /** Not transactional on purpose: the BCrypt check shouldn't hold a database connection. */
    public TokenResponse login(LoginRequest request) {
        A account =
                accounts.findByEmail(Account.normalizeEmail(request.email())).orElse(null);
        String hash = account != null ? account.getPasswordHash() : UNKNOWN_ACCOUNT_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (account == null || !passwordMatches || !account.isActive()) {
            throw new InvalidCredentialsException();
        }
        return startSession(account);
    }

    /** Rotates the refresh token: the presented one is revoked and a new session is issued. */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        UUID accountId = refreshTokens.consume(refreshToken, type);
        A account =
                accounts.findById(accountId).filter(Account::isActive).orElseThrow(InvalidRefreshTokenException::new);
        return startSession(account);
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken, type);
    }

    /** The account an access token from this realm was issued to. */
    @Transactional(readOnly = true)
    public A account(Jwt accessToken) {
        return accounts.findById(UUID.fromString(accessToken.getSubject()))
                .orElseThrow(() -> new NotFoundException("Account not found."));
    }

    /** Authorities granted by an access token from this realm. Read by the realm's filter chain. */
    public Collection<GrantedAuthority> authorities(Jwt accessToken) {
        return List.of();
    }

    /** Extra claims this realm puts in access tokens, read back by {@link #authorities}. */
    protected Map<String, Object> claims(A account) {
        return Map.of();
    }

    private TokenResponse startSession(A account) {
        var access = accessTokens.issue(account.getId(), type, claims(account));
        var refresh = refreshTokens.issue(account.getId(), type);
        return new TokenResponse(
                access.value(),
                "Bearer",
                access.lifetime().toSeconds(),
                refresh.value(),
                refresh.lifetime().toSeconds());
    }
}
