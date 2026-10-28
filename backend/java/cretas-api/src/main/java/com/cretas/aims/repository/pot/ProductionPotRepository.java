package com.cretas.aims.repository.pot;

import com.cretas.aims.entity.pot.ProductionPot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductionPotRepository extends JpaRepository<ProductionPot, Long> {

    Optional<ProductionPot> findByIdAndFactoryId(Long id, String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductionPot p WHERE p.id = :id AND p.factoryId = :factoryId")
    Optional<ProductionPot> findByIdAndFactoryIdForUpdate(@Param("id") Long id, @Param("factoryId") String factoryId);

    List<ProductionPot> findByFactoryIdAndProductionBatchIdOrderByProcessCodeAscPotNumberAscIdAsc(
            String factoryId, Long productionBatchId);

    List<ProductionPot> findByFactoryIdAndProductionBatchIdIn(String factoryId, Collection<Long> productionBatchIds);

    List<ProductionPot> findByFactoryIdAndIdIn(String factoryId, Collection<Long> ids);

    boolean existsByFactoryId(String factoryId);

    List<ProductionPot> findByFactoryIdAndProcessSheetRowId(String factoryId, Long processSheetRowId);
}
