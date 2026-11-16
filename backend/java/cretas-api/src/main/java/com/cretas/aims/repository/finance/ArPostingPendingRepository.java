package com.cretas.aims.repository.finance;

import com.cretas.aims.entity.finance.ArPostingPending;
import com.cretas.aims.entity.finance.ArPostingPendingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArPostingPendingRepository extends JpaRepository<ArPostingPending, String> {

    Optional<ArPostingPending> findFirstByFactoryIdAndDeliveryIdAndStatus(
            String factoryId, String deliveryId, ArPostingPendingStatus status);

    Page<ArPostingPending> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    Page<ArPostingPending> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, ArPostingPendingStatus status, Pageable pageable);

    Page<ArPostingPending> findByFactoryIdAndOwnerIdInOrderByCreatedAtDesc(
            String factoryId, java.util.Collection<Long> ownerIds, Pageable pageable);

    Page<ArPostingPending> findByFactoryIdAndStatusAndOwnerIdInOrderByCreatedAtDesc(
            String factoryId, ArPostingPendingStatus status, java.util.Collection<Long> ownerIds, Pageable pageable);

    long countByFactoryIdAndStatus(String factoryId, ArPostingPendingStatus status);
}
