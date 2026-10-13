package com.rodrigues.keyra.security;

import com.rodrigues.keyra.config.KeyraProperties;
import com.rodrigues.keyra.service.ManagerRealm;
import com.rodrigues.keyra.service.Realm;
import com.rodrigues.keyra.service.UserRealm;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One filter chain per {@link Realm}, then a public chain that denies anything unmatched. Each realm chain
 * only accepts access tokens with its realm's audience, so a manager token is rejected on user endpoints
 * and the reverse. Authorities come from the realm.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private final SigningKeyProvider keys;
    private final KeyraProperties properties;
    private final ProblemDetailsSecurityHandler problems;
    private final Clock clock;

    public SecurityConfig(
            SigningKeyProvider keys, KeyraProperties properties, ProblemDetailsSecurityHandler problems, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.problems = problems;
        this.clock = clock;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new PreHashedBCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain userChain(HttpSecurity http, UserRealm realm) {
        return realmChain(http, realm, "/api/auth", "/register", "/login", "/refresh", "/logout");
    }

    @Bean
    @Order(2)
    SecurityFilterChain managerChain(HttpSecurity http, ManagerRealm realm) {
        return realmChain(http, realm, "/api/manager", "/auth/login", "/auth/refresh", "/auth/logout");
    }

    @Bean
    @Order(3)
    SecurityFilterChain publicChain(HttpSecurity http) {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/keys/validate")
                .permitAll()
                .requestMatchers(
                        HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                .permitAll()
                .requestMatchers("/error")
                .permitAll()
                .anyRequest()
                .denyAll());
        return stateless(http).build();
    }

    /**
     * Everything under {@code prefix} needs a bearer token from {@code realm}, except the POST {@code
     * publicPaths} (login and friends, which authenticate by request body).
     */
    private SecurityFilterChain realmChain(HttpSecurity http, Realm<?> realm, String prefix, String... publicPaths) {
        String[] permitted =
                Arrays.stream(publicPaths).map(path -> prefix + path).toArray(String[]::new);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(realm::authorities);
        http.securityMatcher(prefix + "/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, permitted)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // The resource server's own handlers cover rejected tokens; exceptionHandling (in
                // stateless) covers requests with no token. Both are needed.
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                                jwt.decoder(decoder(realm.type().audience())).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return stateless(http).build();
    }

    private HttpSecurity stateless(HttpSecurity http) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems));
    }

    private JwtDecoder decoder(String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                timestamps,
                new JwtIssuerValidator(properties.issuerUrl()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
        return decoder;
    }
}
