package com.rodrigues.keyra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.rodrigues.keyra.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Health and probes are public for load balancers and Cloud Run; every other actuator endpoint stays denied. */
@IntegrationTest
class HealthIntegrationTests {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthAndProbesArePublic() {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            assertThat(mvc.get().uri(path).exchange())
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("UP"));
        }
    }

    @Test
    void otherActuatorEndpointsAreDenied() {
        for (String path : new String[] {
            "/actuator",
            "/actuator/env",
            "/actuator/beans",
            "/actuator/info",
            "/actuator/metrics",
            "/actuator/prometheus"
        }) {
            assertThat(mvc.get().uri(path).exchange()).hasStatus4xxClientError();
        }
    }
}
