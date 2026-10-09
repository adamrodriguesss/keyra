package com.rodrigues.keyra.controller;

import static com.rodrigues.keyra.controller.AuthApi.refreshBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import com.rodrigues.keyra.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Token lifetimes from the README token policy, checked by moving the clock. */
@IntegrationTest
class TokenExpiryIntegrationTests {

    /** The JWT timestamp validator's default allowance for clock skew. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
    }

    @Test
    void accessTokenStopsWorkingAfterFifteenMinutes() {
        var tokens = api.registerAndLogin();

        clock.advance(Duration.ofMinutes(14));
        assertThat(api.get("/api/auth/me", tokens.accessToken())).hasStatusOk();

        clock.advance(Duration.ofMinutes(1).plus(CLOCK_SKEW).plusSeconds(1));
        assertThat(api.get("/api/auth/me", tokens.accessToken())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void userRefreshTokenExpiresAfterSevenDays() {
        var early = api.registerAndLogin();
        var late = api.registerAndLogin();

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        assertThat(api.post("/api/auth/refresh", refreshBody(early.refreshToken())))
                .hasStatusOk();

        clock.advance(Duration.ofMinutes(2));
        assertThat(api.post("/api/auth/refresh", refreshBody(late.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void managerRefreshTokenExpiresAfterOneDay() {
        var early = api.adminLogin();
        var late = api.adminLogin();

        clock.advance(Duration.ofDays(1).minusMinutes(1));
        assertThat(api.post("/api/manager/auth/refresh", refreshBody(early.refreshToken())))
                .hasStatusOk();

        clock.advance(Duration.ofMinutes(2));
        assertThat(api.post("/api/manager/auth/refresh", refreshBody(late.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
