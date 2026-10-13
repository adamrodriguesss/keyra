package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.ApiKeyResponse;
import com.rodrigues.keyra.dto.CreateApiKeyRequest;
import com.rodrigues.keyra.dto.CreatedApiKeyResponse;
import com.rodrigues.keyra.service.ApiKeyService;
import com.rodrigues.keyra.service.ManagerRealm;
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

/** Admins and the product's assigned managers manage its keys; everyone else gets a 404. */
@RestController
@RequestMapping("/api/manager/products/{productId}/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeys;
    private final ManagerRealm realm;

    public ApiKeyController(ApiKeyService apiKeys, ManagerRealm realm) {
        this.apiKeys = apiKeys;
        this.realm = realm;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @Valid @RequestBody CreateApiKeyRequest request) {
        return apiKeys.create(realm.actor(jwt), productId, request);
    }

    @PostMapping("/{keyId}/rotate")
    public CreatedApiKeyResponse rotate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId, @PathVariable UUID keyId) {
        return apiKeys.rotate(realm.actor(jwt), productId, keyId);
    }

    @PostMapping("/{keyId}/revoke")
    public ApiKeyResponse revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId, @PathVariable UUID keyId) {
        return apiKeys.revoke(realm.actor(jwt), productId, keyId);
    }

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return apiKeys.list(realm.actor(jwt), productId);
    }
}
