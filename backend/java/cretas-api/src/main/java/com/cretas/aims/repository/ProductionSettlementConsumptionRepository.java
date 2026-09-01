package com.cretas.aims.repository;

import com.cretas.aims.entity.ProductionSettlementConsumption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProductionSettlementConsumptionRepository extends JpaRepository<ProductionSettlementConsumption, Long> {
    List<ProductionSettlementConsumption> findBySettlementIdAndDeletedAtIsNull(String settlementId);

    /**
     * 客供结单口径闸用 —— 同一销售订单下【其它已结单计划】已经领用了多少（2026-09-02）。
     *
     * <p>{@code production_settlement_consumptions} 只在 {@code settleProduction()} 结单成功那一刻
     * 才写入，所以按计划 ID 集合查这张表，天然只会读到<b>已经结单</b>的计划 —— 草稿/未结单计划
     * 在这张表里没有任何行。这正是「累计用量只数已结单计划」这条口径的落地方式，不需要额外的
     * 状态过滤。</p>
     */
    List<ProductionSettlementConsumption> findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
            String factoryId, Collection<String> productionPlanIds);

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
