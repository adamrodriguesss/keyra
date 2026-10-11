package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.ValidateKeyRequest;
import com.rodrigues.keyra.dto.ValidateKeyResponse;
import com.rodrigues.keyra.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: product backends call it with a key, not a token. */
@RestController
@RequestMapping("/api/v1/keys")
public class KeyValidationController {

    private final ApiKeyService apiKeys;

    public KeyValidationController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping("/validate")
    public ValidateKeyResponse validate(@Valid @RequestBody ValidateKeyRequest request) {
        return apiKeys.validate(request.product(), request.key());
    }
}
