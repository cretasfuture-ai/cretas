package com.cretas.aims.repository.bom;

import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BomPriceAdjustmentProposalRepository extends JpaRepository<BomPriceAdjustmentProposal, Long> {

    Optional<BomPriceAdjustmentProposal> findByIdAndFactoryId(Long id, String factoryId);

    /** 批准前只取物料 id (标量, 不把建议实体读进持久化上下文), 以便先按收货同一锁序锁物料。 */
    @Query("select p.materialTypeId from BomPriceAdjustmentProposal p where p.id = :id and p.factoryId = :factoryId")
    Optional<String> findMaterialTypeIdByIdAndFactoryId(@Param("id") Long id, @Param("factoryId") String factoryId);

    /** 批准时加锁读建议行: 并发批准同一条建议只有一个能看到 PENDING。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from BomPriceAdjustmentProposal p where p.id = :id and p.factoryId = :factoryId")
    Optional<BomPriceAdjustmentProposal> lockByIdAndFactoryId(@Param("id") Long id, @Param("factoryId") String factoryId);

    Optional<BomPriceAdjustmentProposal> findByFactoryIdAndRecipeItemIdAndStatusAndDeletedAtIsNull(
            String factoryId, Long recipeItemId, BomPriceAdjustmentProposal.Status status);

    Page<BomPriceAdjustmentProposal> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, BomPriceAdjustmentProposal.Status status, Pageable pageable);

    Page<BomPriceAdjustmentProposal> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);
}
