package com.rodrigues.keyra.controller;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Small helpers for calling the auth endpoints in integration tests. */
final class AuthApi {

    static final String PASSWORD = "correct-horse-battery";
    static final String ADMIN_EMAIL = "admin@keyra.local";
    static final String ADMIN_PASSWORD = "local-admin-password";

    private final MockMvcTester mvc;

    AuthApi(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    MvcTestResult post(String path, String json) {
        return mvc.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    MvcTestResult get(String path, String accessToken) {
        return mvc.get()
                .uri(path)
                .header("Authorization", "Bearer " + accessToken)
                .exchange();
    }

    MvcTestResult register(String email, String password) {
        return post("/api/auth/register", credentials(email, password));
    }

    Tokens userLogin(String email, String password) {
        return Tokens.from(post("/api/auth/login", credentials(email, password)));
    }

    Tokens registerAndLogin() {
        String email = uniqueEmail();
        register(email, PASSWORD);
        return userLogin(email, PASSWORD);
    }

    Tokens adminLogin() {
        return Tokens.from(post("/api/manager/auth/login", credentials(ADMIN_EMAIL, ADMIN_PASSWORD)));
    }

    static String credentials(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
    }

    static String refreshBody(String refreshToken) {
        return """
                {"refreshToken": "%s"}""".formatted(refreshToken);
    }

    record Tokens(String accessToken, String refreshToken) {

        static Tokens from(MvcTestResult result) {
            String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
        }
    }
}
