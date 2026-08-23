package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlementConsumption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductionSettlementConsumptionRepository extends JpaRepository<ProductionSettlementConsumption, Long> {
    List<ProductionSettlementConsumption> findBySettlementIdAndDeletedAtIsNull(String settlementId);

    /**
     * 按计划取结单领用行 —— 成本归集用（2026-08-18）。
     *
     * <p>🔴 为什么成本归集要读这张表：结单把领用事实写进
     * {@code production_settlement_consumptions}，而 {@code OrderCostBreakdownService} 一直
     * 按 {@code material_consumptions.production_batch_id} 归集 —— <b>两张表不相交</b>。
     * prod 实测：本表 4 行 / 1 个计划；{@code material_consumptions} 69 行 / 6 个计划，
     * 其中 <b>47 行 production_batch_id 为空</b>（归集本来就看不到）。
     * 净效果是结单产出的成品 {@code unit_cost} 恒为空。
     *
     * <p>⚠️ 派生查询（JPQL），不是 native —— native 不会被启动时校验，
     * 今天有人因此把 prod 的建单从 409 变成 500。
     */
    List<ProductionSettlementConsumption> findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
            String factoryId, String productionPlanId);
}
