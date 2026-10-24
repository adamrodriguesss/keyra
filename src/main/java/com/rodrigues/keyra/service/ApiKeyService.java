package com.rodrigues.keyra.service;

import com.rodrigues.keyra.config.KeyraProperties;
import com.rodrigues.keyra.dto.ApiKeyResponse;
import com.rodrigues.keyra.dto.CreateApiKeyRequest;
import com.rodrigues.keyra.dto.CreateUserApiKeyRequest;
import com.rodrigues.keyra.dto.CreatedApiKeyResponse;
import com.rodrigues.keyra.dto.UpdateApiKeyRequest;
import com.rodrigues.keyra.dto.ValidateKeyResponse;
import com.rodrigues.keyra.entity.ApiKey;
import com.rodrigues.keyra.entity.ApiKeyStatus;
import com.rodrigues.keyra.entity.AuditAction;
import com.rodrigues.keyra.entity.AuditEvent;
import com.rodrigues.keyra.entity.AuditTargetType;
import com.rodrigues.keyra.entity.Product;
import com.rodrigues.keyra.entity.User;
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
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, rotating, revoking, listing and validating API keys. Managers reach a product's keys only via
 * {@link ProductService#require}; users reach only their own keys, on products that enable user keys.
 */
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

    /** Who acts on a key, for the audit trail. Exactly one of the two is set. */
    private record Issuer(Actor manager, User user) {

        UUID id() {
            return manager != null ? manager.id() : user.getId();
        }

        AuditEvent event(AuditAction action, UUID keyId, Instant now) {
            return manager != null
                    ? AuditEvent.byManager(id(), action, AuditTargetType.API_KEY, keyId, now)
                    : AuditEvent.byUser(id(), action, AuditTargetType.API_KEY, keyId, now);
        }
    }

    @Transactional
    public CreatedApiKeyResponse create(Actor actor, UUID productId, CreateApiKeyRequest request) {
        Product product = products.require(actor, productId);
        if (request.neverExpires() && !actor.isAdmin()) {
            throw new AccessDeniedException("Only admins can create keys that never expire.");
        }
        return issue(
                new Issuer(actor, null),
                product,
                request.name(),
                request.scopes(),
                request.rateLimitPerMinute(),
                request.neverExpires() ? null : Duration.ofDays(request.expiresInDays()));
    }

    @Transactional
    public CreatedApiKeyResponse createForUser(User user, String productSlug, CreateUserApiKeyRequest request) {
        Product product = products.requireUserKeys(productSlug);
        if (keys.countUnexpiredActive(product.getId(), user.getId(), clock.instant()) >= product.getMaxKeysPerUser()) {
            throw new ConflictException("Key limit reached.");
        }
        return issue(
                new Issuer(null, user),
                product,
                request.name(),
                request.scopes() == null ? product.getDefaultUserScopes() : request.scopes(),
                request.rateLimitPerMinute(),
                Duration.ofDays(request.expiresInDays()));
    }

    private CreatedApiKeyResponse issue(
            Issuer issuer, Product product, String name, List<String> scopes, int rateLimit, Duration lifetime) {
        Instant now = clock.instant();
        ApiKeys.Generated generated = ApiKeys.generate();
        ApiKey key = new ApiKey(
                product,
                name,
                generated.prefix(),
                generated.hash(),
                scopes,
                rateLimit,
                lifetime == null ? null : now.plus(lifetime),
                issuer.user(),
                issuer.manager() == null ? null : issuer.id(),
                now);
        // shortcut: no retry on prefix collision (62^8 space), add one if it ever fires
        keys.save(key);
        audit.save(issuer.event(AuditAction.CREATE, key.getId(), now));
        return new CreatedApiKeyResponse(generated.key(), ApiKeyResponse.from(key, key.statusAt(now)));
    }

    /** The old secret keeps validating for the configured grace period. */
    // noRollbackFor: a conflict on an expired key must still persist the expiry audit recorded by requireActive.
    @Transactional(noRollbackFor = ConflictException.class)
    public CreatedApiKeyResponse rotate(Actor actor, UUID productId, UUID keyId) {
        return rotate(new Issuer(actor, null), () -> managerKey(actor, productId, keyId));
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public CreatedApiKeyResponse rotateForUser(User user, String productSlug, UUID keyId) {
        return rotate(new Issuer(null, user), () -> userKey(user, productSlug, keyId));
    }

    private CreatedApiKeyResponse rotate(Issuer issuer, Supplier<ApiKey> lookup) {
        Instant now = clock.instant();
        ApiKey key = requireActive(lookup.get(), now);
        ApiKeys.Generated generated = ApiKeys.generate(key.getPrefix());
        key.rotate(generated.hash(), now.plus(properties.keys().rotationGrace()));
        audit.save(issuer.event(AuditAction.ROTATE, key.getId(), now));
        return new CreatedApiKeyResponse(generated.key(), ApiKeyResponse.from(key, key.statusAt(now)));
    }

    // noRollbackFor: a conflict on an expired key must still persist the expiry audit recorded by requireActive.
    @Transactional(noRollbackFor = ConflictException.class)
    public ApiKeyResponse revoke(Actor actor, UUID productId, UUID keyId) {
        return revoke(new Issuer(actor, null), managerKey(actor, productId, keyId));
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public ApiKeyResponse revokeForUser(User user, String productSlug, UUID keyId) {
        return revoke(new Issuer(null, user), userKey(user, productSlug, keyId));
    }

    private ApiKeyResponse revoke(Issuer issuer, ApiKey key) {
        Instant now = clock.instant();
        requireActive(key, now);
        key.revoke(now);
        audit.save(issuer.event(AuditAction.REVOKE, key.getId(), now));
        return ApiKeyResponse.from(key, key.getStatus());
    }

    /** Cancellation: revokes every active key the user holds on the product. */
    @Transactional
    public List<ApiKeyResponse> revokeAllOfUser(Actor actor, UUID productId, UUID userId) {
        products.require(actor, productId);
        Instant now = clock.instant();
        Issuer issuer = new Issuer(actor, null);
        return keys.findAllByProduct_IdAndOwner_IdAndStatus(productId, userId, ApiKeyStatus.ACTIVE).stream()
                .filter(k -> statusOf(k, now) == ApiKeyStatus.ACTIVE)
                .map(k -> revoke(issuer, k))
                .toList();
    }

    /** For upgrades and downgrades: a null field is left unchanged. */
    @Transactional(noRollbackFor = ConflictException.class)
    public ApiKeyResponse update(Actor actor, UUID productId, UUID keyId, UpdateApiKeyRequest request) {
        Instant now = clock.instant();
        ApiKey key = requireActive(managerKey(actor, productId, keyId), now);
        key.updateLimits(
                request.scopes() == null ? key.getScopes() : request.scopes(),
                request.rateLimitPerMinute() == null ? key.getRateLimitPerMinute() : request.rateLimitPerMinute());
        audit.save(new Issuer(actor, null).event(AuditAction.UPDATE, key.getId(), now));
        return ApiKeyResponse.from(key, key.statusAt(now));
    }

    private ApiKey managerKey(Actor actor, UUID productId, UUID keyId) {
        products.require(actor, productId);
        return keys.findByIdAndProduct_Id(keyId, productId).orElseThrow(() -> new NotFoundException("Key not found."));
    }

    private ApiKey userKey(User user, String productSlug, UUID keyId) {
        Product product = products.requireUserKeys(productSlug);
        return keys.findByIdAndProduct_IdAndOwner_Id(keyId, product.getId(), user.getId())
                .orElseThrow(() -> new NotFoundException("Key not found."));
    }

    private ApiKey requireActive(ApiKey key, Instant now) {
        if (statusOf(key, now) != ApiKeyStatus.ACTIVE) {
            throw new ConflictException("Key is not active.");
        }
        return key;
    }

    /**
     * The only place status is derived in this service, so the only place expiry is audited: expiry is derived,
     * not stored, so it is audited once, the first time any request finds the key expired, dated at {@code expires_at}.
     */
    private ApiKeyStatus statusOf(ApiKey key, Instant now) {
        ApiKeyStatus status = key.statusAt(now);
        if (status == ApiKeyStatus.EXPIRED && !key.isExpiryAudited() && keys.markExpiryAudited(key.getId()) == 1) {
            audit.save(AuditEvent.keyExpired(key));
        }
        return status;
    }

    /** All the product's keys, or only {@code ownerUserId}'s when given. */
    @Transactional
    public List<ApiKeyResponse> list(Actor actor, UUID productId, UUID ownerUserId) {
        products.require(actor, productId);
        return respond(
                ownerUserId == null
                        ? keys.findAllByProduct_IdOrderByCreatedAtAsc(productId)
                        : keys.findAllByProduct_IdAndOwner_IdOrderByCreatedAtAsc(productId, ownerUserId));
    }

    @Transactional
    public List<ApiKeyResponse> listForUser(User user, String productSlug) {
        Product product = products.requireUserKeys(productSlug);
        return respond(keys.findAllByProduct_IdAndOwner_IdOrderByCreatedAtAsc(product.getId(), user.getId()));
    }

    private List<ApiKeyResponse> respond(List<ApiKey> found) {
        Instant now = clock.instant();
        return found.stream().map(k -> ApiKeyResponse.from(k, statusOf(k, now))).toList();
    }

    /** Any failed check gives the same {@code INVALID}, so callers can't tell why. A disabled owner fails too. */
    @Transactional
    public ValidateKeyResponse validate(String productSlug, String fullKey) {
        String hash = ApiKeys.hash(fullKey);
        Instant now = clock.instant();
        return ApiKeys.prefixOf(fullKey)
                .flatMap(keys::findByPrefix)
                .filter(k -> statusOf(k, now) == ApiKeyStatus.ACTIVE
                        && k.matches(hash, productSlug, now)
                        && (k.getOwner() == null || k.getOwner().isActive()))
                .<ValidateKeyResponse>map(k -> new ValidateKeyResponse.Valid(
                        k.getId(),
                        productSlug,
                        k.getScopes(),
                        k.getRateLimitPerMinute(),
                        k.getExpiresAt(),
                        k.getOwnerId()))
                .orElse(ValidateKeyResponse.INVALID);
    }
}
