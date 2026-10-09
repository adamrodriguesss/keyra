package com.rodrigues.keyra.service;

import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.SubjectType;
import com.rodrigues.keyra.repository.ManagerRepository;
import com.rodrigues.keyra.security.AccessTokenIssuer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** People who administer Keyra. Their tokens carry their role, which becomes a {@code ROLE_*} authority. */
@Component
public class ManagerRealm extends Realm<Manager> {

    static final String ROLE_CLAIM = "role";

    public ManagerRealm(
            ManagerRepository managers,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokens,
            RefreshTokenService refreshTokens) {
        super(SubjectType.MANAGER, managers, passwordEncoder, accessTokens, refreshTokens);
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
}
