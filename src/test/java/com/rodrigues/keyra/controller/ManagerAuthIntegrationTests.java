package com.rodrigues.keyra.controller;

import static com.rodrigues.keyra.controller.AuthApi.ADMIN_EMAIL;
import static com.rodrigues.keyra.controller.AuthApi.refreshBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ManagerAuthIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
    }

    @Test
    void bootstrapAdminCanLogInAndSeeTheirAccount() {
        var tokens = api.adminLogin();

        assertThat(api.get("/api/manager/me", tokens.accessToken()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo(ADMIN_EMAIL))
                .hasPathSatisfying("$.role", v -> v.assertThat().isEqualTo("ADMIN"));
    }

    @Test
    void managerRefreshTokenLivesOneDayAndRotates() {
        var login = api.post("/api/manager/auth/login", AuthApi.credentials(ADMIN_EMAIL, AuthApi.ADMIN_PASSWORD));
        assertThat(login)
                .bodyJson()
                .hasPathSatisfying("$.refreshExpiresIn", v -> v.assertThat().isEqualTo(86400));
        var tokens = AuthApi.Tokens.from(login);

        assertThat(api.post("/api/manager/auth/refresh", refreshBody(tokens.refreshToken())))
                .hasStatusOk();
        assertThat(api.post("/api/manager/auth/refresh", refreshBody(tokens.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void managerTokenIsRejectedByUserEndpoints() {
        var managerTokens = api.adminLogin();

        assertThat(api.get("/api/auth/me", managerTokens.accessToken()))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying(
                        "$.detail",
                        v -> v.assertThat()
                                .asString()
                                .startsWith("The access token was rejected:")
                                .contains("aud"));
        assertThat(api.get("/api/auth/me", managerTokens.accessToken()))
                .headers()
                .containsHeader("WWW-Authenticate");
        assertThat(api.post("/api/auth/refresh", refreshBody(managerTokens.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void userTokenIsRejectedByManagerEndpoints() {
        var userTokens = api.registerAndLogin();

        assertThat(api.get("/api/manager/me", userTokens.accessToken())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post("/api/manager/auth/refresh", refreshBody(userTokens.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usersCannotLogInAsManagers() {
        var email = AuthApi.uniqueEmail();
        api.register(email, AuthApi.PASSWORD);

        assertThat(api.post("/api/manager/auth/login", AuthApi.credentials(email, AuthApi.PASSWORD)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownPathsAreDenied() {
        assertThat(mvc.get().uri("/api/nope")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
