package com.rodrigues.keyra.dto;

/** {@code key} is the full key, returned only here. */
public record CreatedApiKeyResponse(String key, ApiKeyResponse apiKey) {}
