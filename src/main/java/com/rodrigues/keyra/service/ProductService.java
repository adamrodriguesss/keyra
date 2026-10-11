package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.AssignManagersRequest;
import com.rodrigues.keyra.dto.CreateProductRequest;
import com.rodrigues.keyra.dto.ProductResponse;
import com.rodrigues.keyra.dto.UpdateProductRequest;
import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.Product;
import com.rodrigues.keyra.exception.ConflictException;
import com.rodrigues.keyra.exception.NotFoundException;
import com.rodrigues.keyra.repository.ManagerRepository;
import com.rodrigues.keyra.repository.ProductRepository;
import com.rodrigues.keyra.service.ManagerRealm.Actor;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Products and their manager assignments. Admins see every product, managers only the ones assigned to
 * them. Returns DTOs because the manager set is lazy.
 */
@Service
public class ProductService {

    private final ProductRepository products;
    private final ManagerRepository managers;
    private final Clock clock;

    public ProductService(ProductRepository products, ManagerRepository managers, Clock clock) {
        this.products = products;
        this.managers = managers;
        this.clock = clock;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        Product product = new Product(request.name(), request.slug(), request.description(), clock.instant());
        try {
            return ProductResponse.from(products.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A product with this slug already exists.");
        }
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list(Actor actor) {
        List<Product> visible = actor.isAdmin()
                ? products.findAllByOrderByCreatedAtAsc()
                : products.findAllByManagers_IdOrderByCreatedAtAsc(actor.id());
        return visible.stream().map(ProductResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Actor actor, UUID id) {
        return ProductResponse.from(require(actor, id));
    }

    /**
     * The product, if the actor may work with it. Otherwise a 404, so unassigned managers can't tell which
     * products exist.
     */
    @Transactional(readOnly = true)
    public Product require(Actor actor, UUID id) {
        return (actor.isAdmin() ? products.findById(id) : products.findByIdAndManagers_Id(id, actor.id()))
                .orElseThrow(() -> new NotFoundException("Product not found."));
    }

    /** Partial update: a null field is left unchanged. */
    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        Product product = find(id);
        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        return ProductResponse.from(product);
    }

    /** Replaces the product's managers with exactly the given set. */
    @Transactional
    public ProductResponse assignManagers(UUID id, AssignManagersRequest request) {
        Product product = find(id);
        List<Manager> found = managers.findAllById(request.managerIds());
        if (found.size() != request.managerIds().size()) {
            throw new NotFoundException("Manager not found.");
        }
        product.getManagers().clear();
        product.getManagers().addAll(found);
        return ProductResponse.from(product);
    }

    private Product find(UUID id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product not found."));
    }
}
