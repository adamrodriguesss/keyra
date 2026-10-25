package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.RegisterRequest;
import com.rodrigues.keyra.dto.UserPageResponse;
import com.rodrigues.keyra.dto.UserResponse;
import com.rodrigues.keyra.entity.Account;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.entity.User;
import com.rodrigues.keyra.repository.UserRepository;
import com.rodrigues.keyra.security.AccessTokenIssuer;
import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** End users of the company's products. The only realm with self-registration. */
@Component
public class UserRealm extends Realm<User> {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserRealm(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokens,
            RefreshTokenService refreshTokens,
            Clock clock) {
        super(SubjectType.USER, users, passwordEncoder, accessTokens, refreshTokens);
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Not transactional on purpose: the BCrypt hash shouldn't hold a database connection. */
    public User register(RegisterRequest request) {
        return saveNew(new User(request.email(), passwordEncoder.encode(request.password()), clock.instant()));
    }

    static final int MAX_PAGE_SIZE = 100;

    /** Newest first; out-of-range {@code page} and {@code size} are clamped rather than rejected. */
    @Transactional(readOnly = true)
    public UserPageResponse list(String q, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = users.findByEmailContaining(
                Account.normalizeEmail(q), PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new UserPageResponse(result.map(UserResponse::from).getContent(), p, s, result.getTotalElements());
    }
}
