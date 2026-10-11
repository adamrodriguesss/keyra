package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.CreateManagerRequest;
import com.rodrigues.keyra.dto.ManagerResponse;
import com.rodrigues.keyra.dto.UpdateManagerRequest;
import com.rodrigues.keyra.service.ManagerRealm;
import com.rodrigues.keyra.service.ManagerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/managers")
@PreAuthorize("hasRole('ADMIN')")
public class ManagerController {

    private final ManagerService managers;
    private final ManagerRealm realm;

    public ManagerController(ManagerService managers, ManagerRealm realm) {
        this.managers = managers;
        this.realm = realm;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManagerResponse create(@Valid @RequestBody CreateManagerRequest request) {
        return ManagerResponse.from(realm.create(request));
    }

    @GetMapping
    public List<ManagerResponse> list() {
        return managers.list().stream().map(ManagerResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ManagerResponse get(@PathVariable UUID id) {
        return ManagerResponse.from(realm.find(id));
    }

    @PatchMapping("/{id}")
    public ManagerResponse update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody UpdateManagerRequest request) {
        return ManagerResponse.from(managers.update(realm.actor(jwt), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        managers.delete(realm.actor(jwt), id);
    }
}
