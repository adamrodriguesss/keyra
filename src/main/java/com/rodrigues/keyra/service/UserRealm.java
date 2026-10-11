package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.RegisterRequest;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.entity.User;
import com.rodrigues.keyra.repository.UserRepository;
import com.rodrigues.keyra.security.AccessTokenIssuer;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

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
}
