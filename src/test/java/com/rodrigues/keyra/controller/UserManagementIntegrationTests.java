package com.rodrigues.keyra.controller;

import static com.rodrigues.keyra.controller.AuthApi.credentials;
import static com.rodrigues.keyra.controller.AuthApi.refreshBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.rodrigues.keyra.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class UserManagementIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    AuthApi api;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
    }

    private String userId(String userAccessToken) {
        var me = api.get("/api/auth/me", userAccessToken);
        return JsonPath.read(AuthApi.body(me), "$.id");
    }

    @Test
    void anyManagerCanDisableAndReEnableAUser() {
        String email = AuthApi.uniqueEmail();
        api.register(email, AuthApi.PASSWORD);
        var user = api.userLogin(email, AuthApi.PASSWORD);
        String path = "/api/manager/users/" + userId(user.accessToken());
        String manager = api.createManagerAndLogin("MANAGER").tokens().accessToken();

        assertThat(api.patch(path, "{\"status\": \"DISABLED\"}", manager))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("DISABLED"));
        assertThat(api.post("/api/auth/login", credentials(email, AuthApi.PASSWORD)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post("/api/auth/refresh", refreshBody(user.refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(api.patch(path, "{\"status\": \"ACTIVE\"}", manager)).hasStatusOk();
        assertThat(api.userLogin(email, AuthApi.PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void unknownUserIs404AndUserTokensAreRejected() {
        String admin = api.adminToken();
        assertThat(api.patch(
                        "/api/manager/users/00000000-0000-0000-0000-000000000000", "{\"status\": \"DISABLED\"}", admin))
                .hasStatus(HttpStatus.NOT_FOUND);

        var user = api.registerAndLogin();
        assertThat(api.patch(
                        "/api/manager/users/" + userId(user.accessToken()),
                        "{\"status\": \"DISABLED\"}",
                        user.accessToken()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
