package com.rodrigues.keyra.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.ManagerRole;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ManagerRealmTests {

    private final ManagerRealm realm = new ManagerRealm(null, null, null, null, null);

    @Test
    void roleClaimWrittenAtLoginBecomesARoleAuthority() {
        var manager = new Manager("m@example.com", "hash", ManagerRole.MANAGER, Instant.EPOCH);
        Jwt token = jwtWithClaims(realm.claims(manager).get(ManagerRealm.ROLE_CLAIM));

        assertThat(realm.authorities(token))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MANAGER");
    }

    @Test
    void tokenWithoutRoleGrantsNothing() {
        assertThat(realm.authorities(jwtWithClaims(null))).isEmpty();
    }

    private static Jwt jwtWithClaims(Object role) {
        var builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("someone");
        if (role != null) {
            builder.claim(ManagerRealm.ROLE_CLAIM, role);
        }
        return builder.build();
    }
}
