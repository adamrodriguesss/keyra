package com.rodrigues.keyra.controller;

import static com.rodrigues.keyra.controller.AuthApi.credentials;
import static com.rodrigues.keyra.controller.AuthApi.refreshBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ManagerManagementIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    AuthApi api;
    String admin;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
        admin = api.adminToken();
    }

    private static String newManager(String email, String password, String role) {
        return """
                {"email": "%s", "password": "%s", "role": "%s"}""".formatted(email, password, role);
    }

    @Test
    void adminCreatesListsAndReadsManagersWhoCanThenLogIn() {
        String email = AuthApi.uniqueEmail();
        var created =
                api.post("/api/manager/managers", newManager(email.toUpperCase(), AuthApi.PASSWORD, "MANAGER"), admin);
        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo(email))
                .hasPathSatisfying("$.role", v -> v.assertThat().isEqualTo("MANAGER"))
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("ACTIVE"));
        String id = AuthApi.idOf(created);

        assertThat(api.get("/api/manager/managers/" + id, admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo(email));
        assertThat(api.get("/api/manager/managers", admin))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].id")
                .asList()
                .contains(id);
        assertThat(api.managerLogin(email, AuthApi.PASSWORD).accessToken()).isNotBlank();

        assertThat(api.post("/api/manager/managers", newManager(email, AuthApi.PASSWORD, "ADMIN"), admin))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(api.post("/api/manager/managers", newManager(AuthApi.uniqueEmail(), "short", "MANAGER"), admin))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors.password", v -> v.assertThat().isNotNull());
        assertThat(api.get("/api/manager/managers/00000000-0000-0000-0000-000000000000", admin))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void disablingAManagerBlocksLoginAndRefreshUntilReEnabled() {
        var target = api.createManagerAndLogin("MANAGER");
        String path = "/api/manager/managers/" + target.id();

        assertThat(api.patch(path, "{\"status\": \"DISABLED\"}", admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("DISABLED"));
        assertThat(api.post("/api/manager/auth/login", credentials(target.email(), AuthApi.PASSWORD)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post(
                        "/api/manager/auth/refresh", refreshBody(target.tokens().refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(api.patch(path, "{\"status\": \"ACTIVE\", \"role\": \"ADMIN\"}", admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.role", v -> v.assertThat().isEqualTo("ADMIN"));
        assertThat(api.managerLogin(target.email(), AuthApi.PASSWORD).accessToken())
                .isNotBlank();
    }

    @Test
    void deletingAManagerRemovesTheAccountAndItsSessions() {
        var target = api.createManagerAndLogin("MANAGER");
        String path = "/api/manager/managers/" + target.id();

        assertThat(api.delete(path, admin)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(api.get(path, admin)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/manager/auth/login", credentials(target.email(), AuthApi.PASSWORD)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.post(
                        "/api/manager/auth/refresh", refreshBody(target.tokens().refreshToken())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAdminCannotDisableDemoteOrDeleteThemselves() {
        String self = AuthApi.idOf(api.get("/api/manager/me", admin));
        String path = "/api/manager/managers/" + self;

        assertThat(api.patch(path, "{\"status\": \"DISABLED\"}", admin)).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.patch(path, "{\"role\": \"MANAGER\"}", admin)).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.delete(path, admin)).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.adminLogin().accessToken()).isNotBlank();
    }

    @Test
    void managerRoleCannotManageManagers() {
        var manager = api.createManagerAndLogin("MANAGER");
        String token = manager.tokens().accessToken();
        String path = "/api/manager/managers/" + manager.id();

        assertThat(api.get("/api/manager/managers", token)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(api.post(
                        "/api/manager/managers", newManager(AuthApi.uniqueEmail(), AuthApi.PASSWORD, "ADMIN"), token))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(api.patch(path, "{\"role\": \"ADMIN\"}", token)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(api.delete(path, token)).hasStatus(HttpStatus.FORBIDDEN);
    }
}
