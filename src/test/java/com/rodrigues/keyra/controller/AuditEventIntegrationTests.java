package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.rodrigues.keyra.IntegrationTest;
import com.rodrigues.keyra.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class AuditEventIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MutableClock clock;

    AuthApi api;
    String admin;
    String productId;
    AuthApi.ManagerAccount manager;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
        admin = api.adminToken();
        productId = api.createProduct();
        manager = api.createManagerAndLogin("MANAGER");
        api.put(
                "/api/manager/products/" + productId + "/managers",
                "{\"managerIds\": [\"" + manager.id() + "\"]}",
                admin);
    }

    String token() {
        return manager.tokens().accessToken();
    }

    String keys() {
        return "/api/manager/products/" + productId + "/keys";
    }

    String feed(String query) {
        var result = api.get("/api/manager/products/" + productId + "/audit-events" + query, token());
        assertThat(result).hasStatusOk();
        return AuthApi.body(result);
    }

    @Test
    void lifecycleEventsAreListedNewestFirstWithActorAndKey() {
        var key = api.createKey(productId, token());
        api.post(keys() + "/" + key.id() + "/rotate", "", token());
        api.patch(keys() + "/" + key.id(), "{\"rateLimitPerMinute\": 5}", token());
        api.post(keys() + "/" + key.id() + "/revoke", "", token());

        String body = feed("");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].action"))
                .containsExactly("REVOKE", "UPDATE", "ROTATE", "CREATE");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].actorEmail")).containsOnly(manager.email());
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].keyName")).containsOnly("ci");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].keyPrefix"))
                .containsOnly(key.key().substring(4, 12));
        assertThat((int) JsonPath.read(body, "$.total")).isEqualTo(4);
    }

    @Test
    void expiryIsListedAsSystemWithoutEmail() {
        var key = api.createKey(productId, token());
        clock.advance(Duration.ofDays(91));
        String fresh = api.managerLogin(manager.email(), AuthApi.PASSWORD).accessToken();
        api.get(keys(), fresh);

        String body = AuthApi.body(api.get("/api/manager/products/" + productId + "/audit-events", fresh));
        assertThat((String) JsonPath.read(body, "$.items[0].action")).isEqualTo("EXPIRE");
        assertThat((String) JsonPath.read(body, "$.items[0].actorType")).isEqualTo("SYSTEM");
        assertThat((Object) JsonPath.read(body, "$.items[0].actorEmail")).isNull();
        assertThat((String) JsonPath.read(body, "$.items[0].keyId")).isEqualTo(key.id());
    }

    @Test
    void userKeyEventsShowTheUsersEmail() {
        api.put("/api/manager/products/" + productId + "/user-keys", """
                {"enabled": true, "defaultScopes": ["free"], "maxKeysPerUser": 5}""", admin);
        String slug = JsonPath.read(AuthApi.body(api.get("/api/manager/products/" + productId, admin)), "$.slug");
        String email = AuthApi.uniqueEmail();
        api.register(email, AuthApi.PASSWORD);
        String user = api.userLogin(email, AuthApi.PASSWORD).accessToken();
        assertThat(api.post("/api/products/" + slug + "/keys", "{\"name\": \"mine\"}", user))
                .hasStatus(HttpStatus.CREATED);

        String body = feed("");
        assertThat((String) JsonPath.read(body, "$.items[0].actorType")).isEqualTo("USER");
        assertThat((String) JsonPath.read(body, "$.items[0].actorEmail")).isEqualTo(email);
    }

    @Test
    void keyFilterPagingAndSizeCap() {
        var a = api.createKey(productId, token());
        api.createKey(productId, token());
        api.post(keys() + "/" + a.id() + "/revoke", "", token());

        String filtered = feed("?keyId=" + a.id());
        assertThat((int) JsonPath.read(filtered, "$.total")).isEqualTo(2);

        String paged = feed("?page=1&size=2");
        assertThat((int) JsonPath.read(paged, "$.total")).isEqualTo(3);
        assertThat(JsonPath.<List<?>>read(paged, "$.items")).hasSize(1);
        assertThat((int) JsonPath.read(feed("?size=1000"), "$.size")).isEqualTo(100);

        String otherProduct = api.createProduct();
        String foreign =
                AuthApi.body(api.get("/api/manager/products/" + otherProduct + "/audit-events?keyId=" + a.id(), admin));
        assertThat((int) JsonPath.read(foreign, "$.total")).isZero();
    }

    @Test
    void unassignedManagerGetsNotFoundAndOtherProductsNeverAppear() {
        api.createKey(api.createProduct(), admin);
        assertThat((int) JsonPath.read(feed(""), "$.total")).isZero();

        var stranger = api.createManagerAndLogin("MANAGER");
        assertThat(api.get(
                        "/api/manager/products/" + productId + "/audit-events",
                        stranger.tokens().accessToken()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
