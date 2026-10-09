package com.rodrigues.keyra.dto;

/** Lifetimes are in seconds. */
public record TokenResponse(
        String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {}
