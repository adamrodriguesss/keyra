package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.AuditEventPageResponse;
import com.rodrigues.keyra.service.AuditService;
import com.rodrigues.keyra.service.ManagerRealm;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Same access as the product's keys: admins and assigned managers, everyone else gets a 404. */
@RestController
@RequestMapping("/api/manager/products/{productId}")
public class AuditEventController {

    private final AuditService audit;
    private final ManagerRealm realm;

    public AuditEventController(AuditService audit, ManagerRealm realm) {
        this.audit = audit;
        this.realm = realm;
    }

    @GetMapping("/audit-events")
    public AuditEventPageResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID productId,
            @RequestParam(required = false) UUID keyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.list(realm.actor(jwt), productId, keyId, page, size);
    }
}
