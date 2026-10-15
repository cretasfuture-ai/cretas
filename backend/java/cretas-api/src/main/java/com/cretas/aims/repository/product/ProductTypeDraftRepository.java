package com.cretas.aims.repository.product;

import com.cretas.aims.entity.product.ProductTypeDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductTypeDraftRepository extends JpaRepository<ProductTypeDraft, String> {

    Optional<ProductTypeDraft> findByFactoryIdAndIdempotencyKey(String factoryId, String idempotencyKey);

    Optional<ProductTypeDraft> findByIdAndFactoryId(String id, String factoryId);
}
