package com.cretas.aims.repository;

import com.cretas.aims.entity.InsertSlotPreview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InsertSlotPreviewRepository extends JpaRepository<InsertSlotPreview, String> {

    Optional<InsertSlotPreview> findByIdAndFactoryId(String id, String factoryId);

    long countByFactoryIdAndSlotId(String factoryId, String slotId);

    List<InsertSlotPreview> findByFactoryIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String factoryId, LocalDateTime createdAfter);
}
