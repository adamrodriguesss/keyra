package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.rodrigues.keyra.IntegrationTest;
import com.rodrigues.keyra.entity.AuditAction;
import com.rodrigues.keyra.entity.AuditActorType;
import com.rodrigues.keyra.entity.AuditEvent;
import com.rodrigues.keyra.repository.AuditEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class UserApiKeyIntegrationTests {

    private static final String INVALID = "{\"valid\":false}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AuditEventRepository auditEvents;

    AuthApi api;
    String admin;
    String productId;
    String slug;
    String userId;
    String user;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
        admin = api.adminToken();
        slug = AuthApi.uniqueSlug();
        productId = AuthApi.idOf(api.post("/api/manager/products", AuthApi.productBody(slug), admin));
        enable(admin, 10);
        String email = AuthApi.uniqueEmail();
        userId = AuthApi.idOf(api.register(email, AuthApi.PASSWORD));
        user = api.userLogin(email, AuthApi.PASSWORD).accessToken();
    }

    String settingsPath() {
        return "/api/manager/products/" + productId + "/user-keys";
    }

    String userKeys() {
        return "/api/products/" + slug + "/keys";
    }

    void enable(String token, int max) {
        assertThat(api.put(settingsPath(), """
                {"enabled": true, "defaultScopes": ["free"], "maxKeysPerUser": %d}""".formatted(max), token)).hasStatusOk();
    }

    String mint(String token, String body) {
        var result = api.post(userKeys(), body, token);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return AuthApi.body(result);
    }

    @Test
    void userCreatesListsRotatesAndRevokesOwnKeysWithDefaultScopes() {
        String body = mint(user, "{\"name\": \"mine\"}");
        String key = JsonPath.read(body, "$.key");
        String keyId = JsonPath.read(body, "$.apiKey.id");
        assertThat(api.validateBody(slug, key)).contains("\"valid\":true").contains("\"userId\":\"" + userId + "\"");
        assertThat(api.get(userKeys(), user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].scopes", v -> v.assertThat().asList().containsExactly("free"))
                .hasPathSatisfying("$[0].ownerUserId", v -> v.assertThat().isEqualTo(userId));

        assertThat(api.post(userKeys() + "/" + keyId + "/rotate", "", user)).hasStatusOk();
        assertThat(api.post(userKeys() + "/" + keyId + "/revoke", "", user)).hasStatusOk();
        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
        assertThat(api.post(userKeys() + "/" + keyId + "/revoke", "", user)).hasStatus(HttpStatus.CONFLICT);

        var events = auditEvents.findAllByTargetId(UUID.fromString(keyId));
        assertThat(events)
                .extracting(AuditEvent::getAction)
                .containsExactlyInAnyOrder(AuditAction.CREATE, AuditAction.ROTATE, AuditAction.REVOKE);
        assertThat(events).extracting(AuditEvent::getActorType).containsOnly(AuditActorType.USER);
        assertThat(events).extracting(AuditEvent::getActorId).containsOnly(UUID.fromString(userId));
    }

    @Test
    void userEndpointsAre404ForDisabledInactiveProductsAndOtherUsersKeys() {
        String keyId = JsonPath.read(mint(user, "{\"name\": \"mine\"}"), "$.apiKey.id");
        String other = api.registerAndLogin().accessToken();
        assertThat(api.post(userKeys() + "/" + keyId + "/revoke", "", other)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.get(userKeys(), other)).hasStatusOk().bodyJson().isLenientlyEqualTo("[]");

        api.put(settingsPath(), "{\"enabled\": false, \"defaultScopes\": [], \"maxKeysPerUser\": 10}", admin);
        assertThat(api.post(userKeys(), "{\"name\": \"x\"}", user)).hasStatus(HttpStatus.NOT_FOUND);
        enable(admin, 10);
        api.patch("/api/manager/products/" + productId, "{\"active\": false}", admin);
        assertThat(api.post(userKeys(), "{\"name\": \"x\"}", user)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.post("/api/products/nope/keys", "{\"name\": \"x\"}", user))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void capConflictsAndRealmsAreSeparate() {
        enable(admin, 1);
        mint(user, "{\"name\": \"one\"}");
        assertThat(api.post(userKeys(), "{\"name\": \"two\"}", user)).hasStatus(HttpStatus.CONFLICT);

        assertThat(api.get(userKeys(), admin)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/manager/products/" + productId + "/keys", user))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void disabledOwnersKeyIsInvalidUntilReEnabled() {
        String key = JsonPath.read(mint(user, "{\"name\": \"mine\"}"), "$.key");
        api.patch("/api/manager/users/" + userId, "{\"status\": \"DISABLED\"}", admin);
        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
        api.patch("/api/manager/users/" + userId, "{\"status\": \"ACTIVE\"}", admin);
        assertThat(api.validateBody(slug, key)).contains("\"valid\":true");
    }

    @Test
    void managerPatchesLimitsFiltersByOwnerAndBulkRevokesOneUsersKeys() {
        String body = mint(user, "{\"name\": \"mine\"}");
        String key = JsonPath.read(body, "$.key");
        String keyId = JsonPath.read(body, "$.apiKey.id");
        String managerKeyId = api.createKey(productId, admin).id();
        String otherEmail = AuthApi.uniqueEmail();
        api.register(otherEmail, AuthApi.PASSWORD);
        String otherKey = JsonPath.read(
                mint(api.userLogin(otherEmail, AuthApi.PASSWORD).accessToken(), "{\"name\": \"o\"}"), "$.key");

        String keysPath = "/api/manager/products/" + productId + "/keys";
        assertThat(api.patch(keysPath + "/" + keyId, "{\"scopes\": [\"pro\"], \"rateLimitPerMinute\": 5}", admin))
                .hasStatusOk();
        assertThat(api.validateBody(slug, key)).contains("\"scopes\":[\"pro\"]").contains("\"rateLimitPerMinute\":5");
        assertThat(auditEvents.findAllByTargetId(UUID.fromString(keyId)))
                .extracting(AuditEvent::getAction)
                .contains(AuditAction.UPDATE);

        assertThat(api.get(keysPath + "?ownerUserId=" + userId, admin))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(1);

        assertThat(api.post("/api/manager/products/" + productId + "/users/" + userId + "/keys/revoke", "", admin))
                .hasStatusOk();
        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, otherKey)).contains("\"valid\":true");
        assertThat(api.patch(keysPath + "/" + keyId, "{\"scopes\": []}", admin)).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.patch(keysPath + "/" + managerKeyId, "{\"rateLimitPerMinute\": 9}", admin))
                .hasStatusOk();
    }

    @Test
    void userKeySettingsAreAllowedForAssignedManagersOnly() {
        var assigned = api.createManagerAndLogin("MANAGER");
        var unassigned = api.createManagerAndLogin("MANAGER");
        api.put(
                "/api/manager/products/" + productId + "/managers",
                "{\"managerIds\": [\"" + assigned.id() + "\"]}",
                admin);
        enable(assigned.tokens().accessToken(), 3);
        assertThat(api.put(settingsPath(), """
                {"enabled": true, "defaultScopes": [], "maxKeysPerUser": 3}""", unassigned.tokens().accessToken()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
