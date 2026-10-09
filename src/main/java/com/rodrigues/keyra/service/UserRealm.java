package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.RegisterRequest;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.entity.User;
import com.rodrigues.keyra.exception.EmailAlreadyRegisteredException;
import com.rodrigues.keyra.repository.UserRepository;
import com.rodrigues.keyra.security.AccessTokenIssuer;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * Relies on the unique constraint rather than checking first, so a duplicate email costs the same
     * BCrypt time as a new one and can't be detected by timing.
     */
    public User register(RegisterRequest request) {
        User user = new User(request.email(), passwordEncoder.encode(request.password()), clock.instant());
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException();
        }
    }
}
