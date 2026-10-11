package com.rodrigues.keyra.repository;

import com.rodrigues.keyra.entity.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findAllByOrderByCreatedAtAsc();

    List<Product> findAllByManagers_IdOrderByCreatedAtAsc(UUID managerId);

    Optional<Product> findByIdAndManagers_Id(UUID id, UUID managerId);
}
