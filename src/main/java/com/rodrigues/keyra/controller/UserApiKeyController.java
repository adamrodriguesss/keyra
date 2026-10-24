package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.ApiKeyResponse;
import com.rodrigues.keyra.dto.CreateUserApiKeyRequest;
import com.rodrigues.keyra.dto.CreatedApiKeyResponse;
import com.rodrigues.keyra.service.ApiKeyService;
import com.rodrigues.keyra.service.UserRealm;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A user's own keys on a product that enables user keys; anything else is a 404. */
@RestController
@RequestMapping("/api/products/{slug}/keys")
public class UserApiKeyController {

    private final ApiKeyService apiKeys;
    private final UserRealm realm;

    public UserApiKeyController(ApiKeyService apiKeys, UserRealm realm) {
        this.apiKeys = apiKeys;
        this.realm = realm;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String slug,
            @Valid @RequestBody CreateUserApiKeyRequest request) {
        return apiKeys.createForUser(realm.account(jwt), slug, request);
    }

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug) {
        return apiKeys.listForUser(realm.account(jwt), slug);
    }

    @PostMapping("/{keyId}/rotate")
    public CreatedApiKeyResponse rotate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String slug, @PathVariable UUID keyId) {
        return apiKeys.rotateForUser(realm.account(jwt), slug, keyId);
    }

    @PostMapping("/{keyId}/revoke")
    public ApiKeyResponse revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String slug, @PathVariable UUID keyId) {
        return apiKeys.revokeForUser(realm.account(jwt), slug, keyId);
    }
}
