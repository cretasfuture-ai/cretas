package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionLineCapacityConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ProductionLineCapacityConfigRepository extends JpaRepository<ProductionLineCapacityConfig, Long> {

    /** 本厂对该产品生效的产能行: 产品专用行 + 通用行 (product_type_id 为空), 已生效的。 */
    @Query("select c from ProductionLineCapacityConfig c where c.factoryId = :factoryId"
            + " and (c.productTypeId = :productTypeId or c.productTypeId is null)"
            + " and c.effectiveFrom <= :at")
    List<ProductionLineCapacityConfig> findEffectiveForProduct(@Param("factoryId") String factoryId,
                                                              @Param("productTypeId") String productTypeId,
                                                              @Param("at") LocalDateTime at);
}
