package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.ApiKeyResponse;
import com.rodrigues.keyra.dto.CreateApiKeyRequest;
import com.rodrigues.keyra.dto.CreatedApiKeyResponse;
import com.rodrigues.keyra.dto.ValidateKeyResponse;
import com.rodrigues.keyra.entity.ApiKey;
import com.rodrigues.keyra.entity.Product;
import com.rodrigues.keyra.repository.ApiKeyRepository;
import com.rodrigues.keyra.security.ApiKeys;
import com.rodrigues.keyra.service.ManagerRealm.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating, listing and validating API keys. Managers reach a product's keys only via {@link ProductService#require}. */
@Service
public class ApiKeyService {

    private final ApiKeyRepository keys;
    private final ProductService products;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, ProductService products, Clock clock) {
        this.keys = keys;
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public CreatedApiKeyResponse create(Actor actor, UUID productId, CreateApiKeyRequest request) {
        Product product = products.require(actor, productId);
        if (request.neverExpires() && !actor.isAdmin()) {
            throw new AccessDeniedException("Only admins can create keys that never expire.");
        }
        Instant now = clock.instant();
        Instant expiresAt = request.neverExpires() ? null : now.plus(Duration.ofDays(request.expiresInDays()));
        ApiKeys.Generated generated = ApiKeys.generate();
        ApiKey key = new ApiKey(
                product,
                request.name(),
                generated.prefix(),
                generated.hash(),
                request.scopes(),
                request.rateLimitPerMinute(),
                expiresAt,
                actor.id(),
                now);
        // shortcut: no retry on prefix collision (62^8 space), add one if it ever fires
        keys.save(key);
        return new CreatedApiKeyResponse(generated.key(), ApiKeyResponse.from(key, now));
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(Actor actor, UUID productId) {
        products.require(actor, productId);
        Instant now = clock.instant();
        return keys.findAllByProduct_IdOrderByCreatedAtAsc(productId).stream()
                .map(k -> ApiKeyResponse.from(k, now))
                .toList();
    }

    /** Any failed check gives the same {@code INVALID}, so callers can't tell why. */
    @Transactional(readOnly = true)
    public ValidateKeyResponse validate(String productSlug, String fullKey) {
        String hash = ApiKeys.hash(fullKey);
        Instant now = clock.instant();
        return ApiKeys.prefixOf(fullKey)
                .flatMap(keys::findByPrefix)
                .filter(k -> k.accepts(hash, productSlug, now))
                .<ValidateKeyResponse>map(k -> new ValidateKeyResponse.Valid(
                        k.getId(), productSlug, k.getScopes(), k.getRateLimitPerMinute(), k.getExpiresAt()))
                .orElse(ValidateKeyResponse.INVALID);
    }
}
