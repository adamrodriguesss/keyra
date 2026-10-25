package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.AuditEventPageResponse;
import com.rodrigues.keyra.dto.AuditEventResponse;
import com.rodrigues.keyra.entity.Account;
import com.rodrigues.keyra.entity.AuditActorType;
import com.rodrigues.keyra.repository.AuditEventRepository;
import com.rodrigues.keyra.repository.AuditEventRepository.KeyEvent;
import com.rodrigues.keyra.repository.ManagerRepository;
import com.rodrigues.keyra.repository.UserRepository;
import com.rodrigues.keyra.service.ManagerRealm.Actor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditEventRepository audit;
    private final ProductService products;
    private final ManagerRepository managers;
    private final UserRepository users;

    public AuditService(
            AuditEventRepository audit, ProductService products, ManagerRepository managers, UserRepository users) {
        this.audit = audit;
        this.products = products;
        this.managers = managers;
        this.users = users;
    }

    /** Newest first; out-of-range {@code page} and {@code size} are clamped rather than rejected. */
    @Transactional(readOnly = true)
    public AuditEventPageResponse list(Actor actor, UUID productId, UUID keyId, int page, int size) {
        products.require(actor, productId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = audit.findByProduct(productId, keyId, PageRequest.of(p, s));
        var managerEmails = emails(result.getContent(), AuditActorType.MANAGER, managers::findAllById);
        var userEmails = emails(result.getContent(), AuditActorType.USER, users::findAllById);
        var items = result.map(r -> {
            var e = r.event();
            var emails = switch (e.getActorType()) {
                case MANAGER -> managerEmails;
                case USER -> userEmails;
                case SYSTEM -> Map.<UUID, String>of();
            };
            return new AuditEventResponse(
                    e.getId(),
                    e.getAction(),
                    e.getActorType(),
                    e.getActorId(),
                    e.getActorId() == null ? null : emails.get(e.getActorId()),
                    e.getTargetId(),
                    r.keyName(),
                    r.keyPrefix(),
                    e.getCreatedAt());
        });
        return new AuditEventPageResponse(items.getContent(), p, s, result.getTotalElements());
    }

    private static <A extends Account> Map<UUID, String> emails(
            List<KeyEvent> rows, AuditActorType type, Function<Set<UUID>, List<A>> find) {
        Set<UUID> ids = rows.stream()
                .filter(r -> r.event().getActorType() == type)
                .map(r -> r.event().getActorId())
                .collect(Collectors.toSet());
        return ids.isEmpty()
                ? Map.of()
                : find.apply(ids).stream().collect(Collectors.toMap(Account::getId, Account::getEmail));
    }
}
