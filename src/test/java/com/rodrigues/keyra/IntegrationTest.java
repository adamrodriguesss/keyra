package com.rodrigues.keyra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Full application context against a Testcontainers Postgres, with MockMvc and a {@link MutableClock}. All tests share one context. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
// Outranks ./config/application.properties, so tests use known settings on every machine.
@TestPropertySource(locations = "classpath:application-local.properties")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public @interface IntegrationTest {}
