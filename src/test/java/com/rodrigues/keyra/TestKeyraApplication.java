package com.rodrigues.keyra;

import org.springframework.boot.SpringApplication;

public class TestKeyraApplication {

    public static void main(String[] args) {
        SpringApplication.from(KeyraApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
