package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.AssignManagersRequest;
import com.rodrigues.keyra.dto.CreateProductRequest;
import com.rodrigues.keyra.dto.ProductResponse;
import com.rodrigues.keyra.dto.UpdateProductRequest;
import com.rodrigues.keyra.service.ManagerRealm;
import com.rodrigues.keyra.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Creating, changing and assigning products is admin-only; reading is limited to the caller's products. */
@RestController
@RequestMapping("/api/manager/products")
public class ProductController {

    private final ProductService products;
    private final ManagerRealm realm;

    public ProductController(ProductService products, ManagerRealm realm) {
        this.products = products;
        this.realm = realm;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return products.create(request);
    }

    @GetMapping
    public List<ProductResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return products.list(realm.actor(jwt));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return products.get(realm.actor(jwt), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return products.update(id, request);
    }

    @PutMapping("/{id}/managers")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse assignManagers(@PathVariable UUID id, @Valid @RequestBody AssignManagersRequest request) {
        return products.assignManagers(id, request);
    }
}
