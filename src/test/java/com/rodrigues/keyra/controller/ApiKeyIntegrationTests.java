package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.rodrigues.keyra.IntegrationTest;
import com.rodrigues.keyra.MutableClock;
import com.rodrigues.keyra.entity.AuditAction;
import com.rodrigues.keyra.entity.AuditActorType;
import com.rodrigues.keyra.entity.AuditEvent;
import com.rodrigues.keyra.repository.AuditEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ApiKeyIntegrationTests {

    private static final String INVALID = "{\"valid\":false}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    AuditEventRepository auditEvents;

    AuthApi api;
    String admin;
    String productId;
    String slug;
    AuthApi.ManagerAccount manager;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
        admin = api.adminToken();
        slug = AuthApi.uniqueSlug();
        productId = AuthApi.idOf(api.post("/api/manager/products", AuthApi.productBody(slug), admin));
        manager = api.createManagerAndLogin("MANAGER");
        api.put(
                "/api/manager/products/" + productId + "/managers",
                "{\"managerIds\": [\"" + manager.id() + "\"]}",
                admin);
    }

    String keysPath() {
        return "/api/manager/products/" + productId + "/keys";
    }

    @Test
    void assignedManagerCreatesListsAndValidatesAKey() {
        var created = api.post(keysPath(), """
                {"name": "ci", "scopes": ["orders:read"]}""", manager.tokens().accessToken());
        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying(
                        "$.key", v -> v.assertThat().asString().matches("^kyr_[A-Za-z0-9]{8}_[A-Za-z0-9]{43}$"))
                .hasPathSatisfying(
                        "$.apiKey.rateLimitPerMinute", v -> v.assertThat().isEqualTo(60))
                .hasPathSatisfying("$.apiKey.status", v -> v.assertThat().isEqualTo("ACTIVE"));
        String body = AuthApi.body(created);
        String key = JsonPath.read(body, "$.key");
        String keyId = JsonPath.read(body, "$.apiKey.id");
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.apiKey.expiresAt"));
        Instant createdAt = Instant.parse(JsonPath.read(body, "$.apiKey.createdAt"));
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofDays(90));

        assertThat(api.get(keysPath(), manager.tokens().accessToken()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].prefix", v -> v.assertThat().isEqualTo(key.substring(4, 12)))
                .doesNotHavePath("$[0].key")
                .doesNotHavePath("$[0].keyHash");

        assertThat(api.post("/api/v1/keys/validate", """
                        {"product": "%s", "key": "%s"}""".formatted(slug, key)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.valid", v -> v.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.keyId", v -> v.assertThat().isEqualTo(keyId))
                .hasPathSatisfying("$.product", v -> v.assertThat().isEqualTo(slug))
                .hasPathSatisfying("$.scopes", v -> v.assertThat().asList().containsExactly("orders:read"));
    }

    @Test
    void creationIsLimitedToAssignedManagersAndNeverExpiresToAdmins() {
        var other = api.createManagerAndLogin("MANAGER").tokens().accessToken();
        assertThat(api.post(keysPath(), "{\"name\": \"x\"}", other)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.get(keysPath(), other)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.get(keysPath(), api.registerAndLogin().accessToken())).hasStatus(HttpStatus.UNAUTHORIZED);

        String never = "{\"name\": \"x\", \"neverExpires\": true}";
        assertThat(api.post(keysPath(), never, manager.tokens().accessToken())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(api.post(keysPath(), never, admin))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.apiKey.expiresAt", v -> v.assertThat().isNull());

        assertThat(api.post(keysPath(), "{\"name\": \"x\", \"expiresInDays\": 366}", admin))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(api.post(keysPath(), "{\"name\": \"x\", \"neverExpires\": true, \"expiresInDays\": 30}", admin))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors", v -> v.assertThat().isNotNull());
    }

    @Test
    void everyValidationFailureReturnsOnlyValidFalse() {
        String key = api.createKey(productId, managerToken()).key();
        String otherSlug = AuthApi.uniqueSlug();
        api.post("/api/manager/products", AuthApi.productBody(otherSlug), admin);
        String tampered = key.substring(0, key.length() - 1) + (key.endsWith("a") ? "b" : "a");
        String unknownPrefix = "kyr_" + "0".repeat(8) + key.substring(12);

        assertThat(api.validateBody(slug, "not-a-key")).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, unknownPrefix)).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, tampered)).isEqualTo(INVALID);
        assertThat(api.validateBody(otherSlug, key)).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, key)).contains("\"valid\":true");

        api.patch("/api/manager/products/" + productId, "{\"active\": false}", admin);
        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
    }

    @Test
    void expiredKeyIsInvalid() {
        String key = api.createKey(productId, managerToken()).key();
        assertThat(api.validateBody(slug, key)).contains("\"valid\":true");
        clock.advance(Duration.ofDays(91));
        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
        String fresh = api.managerLogin(manager.email(), AuthApi.PASSWORD).accessToken();
        assertThat(api.get(keysPath(), fresh))
                .bodyJson()
                .hasPathSatisfying("$[0].status", v -> v.assertThat().isEqualTo("EXPIRED"));
    }

    @Test
    void neverExpiringKeyValidatesWithExplicitNullExpiry() {
        String key = JsonPath.read(
                AuthApi.body(api.post(keysPath(), "{\"name\": \"x\", \"neverExpires\": true}", admin)), "$.key");
        assertThat(api.validateBody(slug, key)).contains("\"valid\":true").contains("\"expiresAt\":null");
    }

    static final Duration GRACE = Duration.ofHours(24);

    String managerToken() {
        return manager.tokens().accessToken();
    }

    String rotate(String keyId) {
        var result = api.post(keysPath() + "/" + keyId + "/rotate", "", managerToken());
        assertThat(result).hasStatusOk();
        return JsonPath.read(AuthApi.body(result), "$.key");
    }

    @Test
    void rotationKeepsTheOldSecretValidOnlyForTheGracePeriod() {
        var created = api.createKey(productId, managerToken());
        String oldKey = created.key();
        var result = api.post(keysPath() + "/" + created.id() + "/rotate", "", managerToken());
        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.apiKey.id", v -> v.assertThat().isEqualTo(created.id()))
                .hasPathSatisfying("$.apiKey.prefix", v -> v.assertThat().isEqualTo(oldKey.substring(4, 12)));
        String newKey = JsonPath.read(AuthApi.body(result), "$.key");
        assertThat(newKey).isNotEqualTo(oldKey).startsWith(oldKey.substring(0, 12));

        assertThat(api.validateBody(slug, oldKey)).contains("\"valid\":true");
        assertThat(api.validateBody(slug, newKey)).contains("\"valid\":true");
        clock.advance(GRACE.plusSeconds(1));
        assertThat(api.validateBody(slug, oldKey)).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, newKey)).contains("\"valid\":true");
    }

    @Test
    void rotatingAgainInsideTheGracePeriodDropsTheFirstSecret() {
        var created = api.createKey(productId, managerToken());
        String second = rotate(created.id());
        String third = rotate(created.id());
        assertThat(api.validateBody(slug, created.key())).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, second)).contains("\"valid\":true");
        assertThat(api.validateBody(slug, third)).contains("\"valid\":true");
    }

    @Test
    void revokingKillsEverySecretAndFurtherChangesConflict() {
        var created = api.createKey(productId, managerToken());
        String newKey = rotate(created.id());
        String revokePath = keysPath() + "/" + created.id() + "/revoke";
        assertThat(api.post(revokePath, "", managerToken()))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("REVOKED"));
        assertThat(api.validateBody(slug, created.key())).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, newKey)).isEqualTo(INVALID);
        assertThat(api.get(keysPath(), managerToken()))
                .bodyJson()
                .hasPathSatisfying("$[0].status", v -> v.assertThat().isEqualTo("REVOKED"));

        assertThat(api.post(revokePath, "", managerToken())).hasStatus(HttpStatus.CONFLICT);
        assertThat(api.post(keysPath() + "/" + created.id() + "/rotate", "", managerToken()))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void rotatingAnExpiredKeyConflictsButStillAuditsTheExpiry() {
        var created = api.createKey(productId, managerToken());
        clock.advance(Duration.ofDays(91));
        String fresh = api.managerLogin(manager.email(), AuthApi.PASSWORD).accessToken();
        assertThat(api.post(keysPath() + "/" + created.id() + "/rotate", "", fresh))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(expiryEvents(created.id())).hasSize(1);
    }

    List<AuditEvent> expiryEvents(String keyId) {
        return auditEvents.findAllByTargetId(UUID.fromString(keyId)).stream()
                .filter(e -> e.getAction() == AuditAction.EXPIRE)
                .toList();
    }

    @Test
    void expiryIsAuditedOnceBySystemAtTheExpiryTimeWhenValidationFirstSeesIt() {
        String body = AuthApi.body(api.post(keysPath(), "{\"name\": \"ci\"}", managerToken()));
        String key = JsonPath.read(body, "$.key");
        String keyId = JsonPath.read(body, "$.apiKey.id");
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.apiKey.expiresAt"));
        clock.advance(Duration.ofDays(91));
        assertThat(expiryEvents(keyId)).isEmpty();

        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
        assertThat(api.validateBody(slug, key)).isEqualTo(INVALID);
        assertThat(expiryEvents(keyId)).singleElement().satisfies(e -> {
            assertThat(e.getActorId()).isNull();
            assertThat(e.getActorType()).isEqualTo(AuditActorType.SYSTEM);
            assertThat(e.getCreatedAt()).isEqualTo(expiresAt);
        });
    }

    @Test
    void listingAuditsExpiryOnceAndRevokedKeysNeverExpire() {
        String expiring = api.createKey(productId, managerToken()).id();
        String revoked = api.createKey(productId, managerToken()).id();
        api.post(keysPath() + "/" + revoked + "/revoke", "", managerToken());
        clock.advance(Duration.ofDays(91));
        String fresh = api.managerLogin(manager.email(), AuthApi.PASSWORD).accessToken();
        api.get(keysPath(), fresh);
        api.get(keysPath(), fresh);
        assertThat(expiryEvents(expiring)).hasSize(1);
        assertThat(expiryEvents(revoked)).isEmpty();
    }

    @Test
    void rotateAndRevokeAreLimitedToTheKeysOwnProductAndAssignedManagers() {
        String keyId = api.createKey(productId, managerToken()).id();
        var other = api.createManagerAndLogin("MANAGER").tokens().accessToken();
        for (String action : new String[] {"rotate", "revoke"}) {
            assertThat(api.post(keysPath() + "/" + keyId + "/" + action, "", other))
                    .hasStatus(HttpStatus.NOT_FOUND);
        }
        String otherProduct =
                AuthApi.idOf(api.post("/api/manager/products", AuthApi.productBody(AuthApi.uniqueSlug()), admin));
        assertThat(api.post("/api/manager/products/" + otherProduct + "/keys/" + keyId + "/revoke", "", admin))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRotateAndRevokeAreAudited() {
        String keyId = api.createKey(productId, managerToken()).id();
        rotate(keyId);
        api.post(keysPath() + "/" + keyId + "/revoke", "", managerToken());
        var events = auditEvents.findAllByTargetId(UUID.fromString(keyId));
        assertThat(events)
                .extracting(AuditEvent::getAction)
                .containsExactlyInAnyOrder(AuditAction.CREATE, AuditAction.ROTATE, AuditAction.REVOKE);
        assertThat(events).extracting(AuditEvent::getActorId).containsOnly(UUID.fromString(manager.id()));
    }
}
