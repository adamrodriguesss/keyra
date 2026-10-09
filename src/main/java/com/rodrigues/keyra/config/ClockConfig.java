package com.rodrigues.keyra.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * The only source of "now". Ticks in microseconds, the precision Postgres stores, so saved and
     * reloaded timestamps are equal. Tests replace it with a clock they can advance.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
