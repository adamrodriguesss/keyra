package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.CreateManagerRequest;
import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.ManagerRole;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.repository.ManagerRepository;
import com.rodrigues.keyra.security.AccessTokenIssuer;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** People who administer Keyra. Their tokens carry their role, which becomes a {@code ROLE_*} authority. */
@Component
public class ManagerRealm extends Realm<Manager> {

    static final String ROLE_CLAIM = "role";

    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public ManagerRealm(
            ManagerRepository managers,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokens,
            RefreshTokenService refreshTokens,
            Clock clock) {
        super(SubjectType.MANAGER, managers, passwordEncoder, accessTokens, refreshTokens);
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Not transactional on purpose: the BCrypt hash shouldn't hold a database connection. */
    public Manager create(CreateManagerRequest request) {
        return saveNew(new Manager(
                request.email(), passwordEncoder.encode(request.password()), request.role(), clock.instant()));
    }

    /** The manager calling with this access token, as of when the token was issued. */
    public Actor actor(Jwt accessToken) {
        return new Actor(
                UUID.fromString(accessToken.getSubject()),
                ManagerRole.valueOf(accessToken.getClaimAsString(ROLE_CLAIM)));
    }

    @Override
    protected Map<String, Object> claims(Manager manager) {
        return Map.of(ROLE_CLAIM, manager.getRole().name());
    }

    @Override
    public Collection<GrantedAuthority> authorities(Jwt accessToken) {
        String role = accessToken.getClaimAsString(ROLE_CLAIM);
        return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    public record Actor(UUID id, ManagerRole role) {

        public boolean isAdmin() {
            return role == ManagerRole.ADMIN;
        }
    }
}
