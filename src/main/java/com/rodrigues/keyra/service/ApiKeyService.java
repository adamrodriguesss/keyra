package com.rodrigues.keyra.service;

import com.rodrigues.keyra.config.KeyraProperties;
import com.rodrigues.keyra.dto.ApiKeyResponse;
import com.rodrigues.keyra.dto.CreateApiKeyRequest;
import com.rodrigues.keyra.dto.CreatedApiKeyResponse;
import com.rodrigues.keyra.dto.ValidateKeyResponse;
import com.rodrigues.keyra.entity.ApiKey;
import com.rodrigues.keyra.entity.ApiKeyStatus;
import com.rodrigues.keyra.entity.AuditAction;
import com.rodrigues.keyra.entity.AuditActorType;
import com.rodrigues.keyra.entity.AuditEvent;
import com.rodrigues.keyra.entity.Product;
import com.rodrigues.keyra.exception.ConflictException;
import com.rodrigues.keyra.exception.NotFoundException;
import com.rodrigues.keyra.repository.ApiKeyRepository;
import com.rodrigues.keyra.repository.AuditEventRepository;
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

/** Creating, rotating, revoking, listing and validating API keys. Managers reach a product's keys only via {@link ProductService#require}. */
@Service
public class ApiKeyService {

    private final ApiKeyRepository keys;
    private final AuditEventRepository audit;
    private final ProductService products;
    private final KeyraProperties properties;
    private final Clock clock;

    public ApiKeyService(
            ApiKeyRepository keys,
            AuditEventRepository audit,
            ProductService products,
            KeyraProperties properties,
            Clock clock) {
        this.keys = keys;
        this.audit = audit;
        this.products = products;
        this.properties = properties;
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
        audit(actor, AuditAction.CREATE, key, now);
        return new CreatedApiKeyResponse(generated.key(), ApiKeyResponse.from(key, now));
    }

    /** The old secret keeps validating for the configured grace period. */
    @Transactional(noRollbackFor = ConflictException.class)
    public CreatedApiKeyResponse rotate(Actor actor, UUID productId, UUID keyId) {
        Instant now = clock.instant();
        ApiKey key = requireActive(actor, productId, keyId, now);
        ApiKeys.Generated generated = ApiKeys.generate(key.getPrefix());
        key.rotate(generated.hash(), now.plus(properties.keys().rotationGrace()));
        audit(actor, AuditAction.ROTATE, key, now);
        return new CreatedApiKeyResponse(generated.key(), ApiKeyResponse.from(key, now));
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public ApiKeyResponse revoke(Actor actor, UUID productId, UUID keyId) {
        Instant now = clock.instant();
        ApiKey key = requireActive(actor, productId, keyId, now);
        key.revoke(now);
        audit(actor, AuditAction.REVOKE, key, now);
        return ApiKeyResponse.from(key, now);
    }

    private ApiKey requireActive(Actor actor, UUID productId, UUID keyId, Instant now) {
        products.require(actor, productId);
        ApiKey key =
                keys.findByIdAndProduct_Id(keyId, productId).orElseThrow(() -> new NotFoundException("Key not found."));
        auditIfExpired(key, now);
        if (key.getStatus() != ApiKeyStatus.ACTIVE || key.isExpired(now)) {
            throw new ConflictException("Key is not active.");
        }
        return key;
    }

    private void audit(Actor actor, AuditAction action, ApiKey key, Instant now) {
        this.audit.save(
                new AuditEvent(actor.id(), AuditActorType.MANAGER, action, AuditEvent.API_KEY, key.getId(), now));
    }

    /**
     * Expiry is derived, not stored, so it is audited the first time any request finds the key expired. The
     * event is dated at the expiry itself, not at when it was noticed.
     */
    private void auditIfExpired(ApiKey key, Instant now) {
        if (key.isExpired(now) && !key.isExpiryAudited() && keys.markExpiryAudited(key.getId()) == 1) {
            audit.save(new AuditEvent(
                    null,
                    AuditActorType.SYSTEM,
                    AuditAction.EXPIRE,
                    AuditEvent.API_KEY,
                    key.getId(),
                    key.getExpiresAt()));
        }
    }

    @Transactional
    public List<ApiKeyResponse> list(Actor actor, UUID productId) {
        products.require(actor, productId);
        Instant now = clock.instant();
        List<ApiKey> found = keys.findAllByProduct_IdOrderByCreatedAtAsc(productId);
        found.forEach(k -> auditIfExpired(k, now));
        return found.stream().map(k -> ApiKeyResponse.from(k, now)).toList();
    }

    /** Any failed check gives the same {@code INVALID}, so callers can't tell why. */
    @Transactional
    public ValidateKeyResponse validate(String productSlug, String fullKey) {
        String hash = ApiKeys.hash(fullKey);
        Instant now = clock.instant();
        return ApiKeys.prefixOf(fullKey)
                .flatMap(keys::findByPrefix)
                .map(k -> {
                    auditIfExpired(k, now);
                    return k;
                })
                .filter(k -> k.accepts(hash, productSlug, now))
                .<ValidateKeyResponse>map(k -> new ValidateKeyResponse.Valid(
                        k.getId(), productSlug, k.getScopes(), k.getRateLimitPerMinute(), k.getExpiresAt()))
                .orElse(ValidateKeyResponse.INVALID);
    }
}
