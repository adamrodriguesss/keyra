package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Validation always answers 200, so its outcome gets its own counter. The context is shared: assert deltas. */
@IntegrationTest
class MetricsIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MeterRegistry meters;

    @Test
    void validationsAreCountedByOutcome() {
        AuthApi api = new AuthApi(mvc);
        String admin = api.adminToken();
        String slug = AuthApi.uniqueSlug();
        String productId = AuthApi.idOf(api.post("/api/manager/products", AuthApi.productBody(slug), admin));
        String key = api.createKey(productId, admin).key();
        double valid = validations("valid");
        double invalid = validations("invalid");

        api.validateBody(slug, key);
        api.validateBody(slug, "not-a-key");

        assertThat(validations("valid")).isEqualTo(valid + 1);
        assertThat(validations("invalid")).isEqualTo(invalid + 1);
    }

    private double validations(String outcome) {
        var counter =
                meters.find("keyra.key.validations").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
