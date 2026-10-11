package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ProductIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    AuthApi api;
    String admin;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mvc);
        admin = api.adminToken();
    }

    @Test
    void adminCreatesReadsAndUpdatesAProduct() {
        String slug = AuthApi.uniqueSlug();
        var created = api.post("/api/manager/products", AuthApi.productBody(slug), admin);
        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.slug", v -> v.assertThat().isEqualTo(slug))
                .hasPathSatisfying("$.active", v -> v.assertThat().isEqualTo(true))
                .hasPathSatisfying("$.managerIds", v -> v.assertThat().asList().isEmpty());
        String id = AuthApi.idOf(created);

        assertThat(api.get("/api/manager/products/" + id, admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("Orders"));
        assertThat(api.get("/api/manager/products", admin))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].slug")
                .asList()
                .contains(slug);

        // Slug is immutable: an unknown "slug" property is ignored and the old one stays.
        assertThat(api.patch("/api/manager/products/" + id, """
                        {"name": "Orders v2", "active": false, "slug": "changed"}""", admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("Orders v2"))
                .hasPathSatisfying("$.active", v -> v.assertThat().isEqualTo(false))
                .hasPathSatisfying("$.description", v -> v.assertThat().isEqualTo("Order service"))
                .hasPathSatisfying("$.slug", v -> v.assertThat().isEqualTo(slug));
    }

    @Test
    void duplicateSlugIs409AndInvalidInputIs400() {
        String slug = AuthApi.uniqueSlug();
        api.post("/api/manager/products", AuthApi.productBody(slug), admin);

        assertThat(api.post("/api/manager/products", AuthApi.productBody(slug), admin))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(api.post("/api/manager/products", """
                        {"name": " ", "slug": "Not A Slug"}""", admin))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors.name", v -> v.assertThat().isNotNull())
                .hasPathSatisfying("$.errors.slug", v -> v.assertThat().isNotNull());
    }

    @Test
    void managerRoleCannotCreateOrUpdateAndUserTokenIsRejected() {
        String id = api.createProduct();
        var manager = api.createManagerAndLogin("MANAGER").tokens().accessToken();

        assertThat(api.post("/api/manager/products", AuthApi.productBody(AuthApi.uniqueSlug()), manager))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(api.patch("/api/manager/products/" + id, "{\"name\": \"x\"}", manager))
                .hasStatus(HttpStatus.FORBIDDEN);

        var user = api.registerAndLogin().accessToken();
        assertThat(api.get("/api/manager/products", user)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void assignmentReplacesTheManagerSetAndControlsVisibility() {
        String id = api.createProduct();
        String otherId = api.createProduct();
        var assigned = api.createManagerAndLogin("MANAGER");
        var unassigned = api.createManagerAndLogin("MANAGER");

        assertThat(api.put(
                        "/api/manager/products/" + id + "/managers",
                        "{\"managerIds\": [\"" + assigned.id() + "\"]}",
                        admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.managerIds", v -> v.assertThat().asList().containsExactly(assigned.id()));

        String token = assigned.tokens().accessToken();
        assertThat(api.get("/api/manager/products/" + id, token)).hasStatusOk();
        assertThat(api.get("/api/manager/products/" + otherId, token)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/manager/products", token))
                .bodyJson()
                .extractingPath("$[*].id")
                .asList()
                .containsExactly(id);
        assertThat(api.get("/api/manager/products/" + id, unassigned.tokens().accessToken()))
                .hasStatus(HttpStatus.NOT_FOUND);

        // An empty set clears the assignments.
        assertThat(api.put("/api/manager/products/" + id + "/managers", "{\"managerIds\": []}", admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.managerIds", v -> v.assertThat().asList().isEmpty());
        assertThat(api.get("/api/manager/products/" + id, token)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignmentRejectsUnknownIdsAndNonAdmins() {
        String id = api.createProduct();
        String unknown = "00000000-0000-0000-0000-000000000000";

        assertThat(api.put(
                        "/api/manager/products/" + id + "/managers", "{\"managerIds\": [\"" + unknown + "\"]}", admin))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(api.put("/api/manager/products/" + unknown + "/managers", "{\"managerIds\": []}", admin))
                .hasStatus(HttpStatus.NOT_FOUND);

        var manager = api.createManagerAndLogin("MANAGER").tokens().accessToken();
        assertThat(api.put("/api/manager/products/" + id + "/managers", "{\"managerIds\": []}", manager))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
