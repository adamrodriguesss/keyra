package com.rodrigues.keyra.controller;

import com.jayway.jsonpath.JsonPath;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Small helpers for calling the auth endpoints in integration tests. */
final class AuthApi {

    static final String PASSWORD = "correct-horse-battery";
    static final String ADMIN_EMAIL = "admin@keyra.local";
    static final String ADMIN_PASSWORD = "local-admin-password";

    private final MockMvcTester mvc;
    private String adminToken;

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

    MvcTestResult post(String path, String json, String accessToken) {
        return send(HttpMethod.POST, path, json, accessToken);
    }

    MvcTestResult patch(String path, String json, String accessToken) {
        return send(HttpMethod.PATCH, path, json, accessToken);
    }

    MvcTestResult put(String path, String json, String accessToken) {
        return send(HttpMethod.PUT, path, json, accessToken);
    }

    MvcTestResult delete(String path, String accessToken) {
        return send(HttpMethod.DELETE, path, null, accessToken);
    }

    private MvcTestResult send(HttpMethod method, String path, String json, String accessToken) {
        var request = mvc.method(method).uri(path).header("Authorization", "Bearer " + accessToken);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
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

    /** The bootstrap admin's access token, logged in once per instance (BCrypt makes each login slow). */
    String adminToken() {
        if (adminToken == null) {
            adminToken = adminLogin().accessToken();
        }
        return adminToken;
    }

    Tokens managerLogin(String email, String password) {
        return Tokens.from(post("/api/manager/auth/login", credentials(email, password)));
    }

    /** Creates a manager as the bootstrap admin and logs them in. */
    ManagerAccount createManagerAndLogin(String role) {
        String email = uniqueEmail();
        MvcTestResult created = post("/api/manager/managers", """
                {"email": "%s", "password": "%s", "role": "%s"}""".formatted(email, PASSWORD, role), adminToken());
        return new ManagerAccount(idOf(created), email, managerLogin(email, PASSWORD));
    }

    static String uniqueSlug() {
        return "p-" + UUID.randomUUID().toString().substring(0, 12);
    }

    static String productBody(String slug) {
        return """
                {"name": "Orders", "slug": "%s", "description": "Order service"}""".formatted(slug);
    }

    /** Creates a product as the bootstrap admin and returns its id. */
    String createProduct() {
        return idOf(post("/api/manager/products", productBody(uniqueSlug()), adminToken()));
    }

    /** Creates a key with default settings as the given manager. */
    CreatedKey createKey(String productId, String accessToken) {
        MvcTestResult created = post("/api/manager/products/" + productId + "/keys", """
                {"name": "ci", "scopes": ["orders:read"]}""", accessToken);
        String body = body(created);
        return new CreatedKey(JsonPath.read(body, "$.apiKey.id"), JsonPath.read(body, "$.key"));
    }

    /** Validates a key and returns the raw JSON response body. */
    String validateBody(String slug, String key) {
        MvcTestResult result = post("/api/v1/keys/validate", """
                {"product": "%s", "key": "%s"}""".formatted(slug, key));
        return body(result);
    }

    static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String idOf(MvcTestResult result) {
        return JsonPath.read(body(result), "$.id");
    }

    static String credentials(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
    }

    static String refreshBody(String refreshToken) {
        return """
                {"refreshToken": "%s"}""".formatted(refreshToken);
    }

    record CreatedKey(String id, String key) {}

    record ManagerAccount(String id, String email, Tokens tokens) {}

    record Tokens(String accessToken, String refreshToken) {

        static Tokens from(MvcTestResult result) {
            String body = body(result);
            return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
        }
    }
}
