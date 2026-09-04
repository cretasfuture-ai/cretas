package com.cretas.aims.dto.production;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ProductionCostRecalculationResponse {
    private String productionPlanId;
    private String settlementId;
    private BigDecimal authoritativeTotalCost;
    private BigDecimal sharedUnitCost;
    private int outputLineCount;
    private int finishedGoodsBatchCount;
    private int reworkWipCount;
    private int productionBatchCount;

    /**
     * 🔴 审计透明度补丁 (2026-09-04, PLAN-1788305552728-D94F7E22 复现): 原料成本归集的逐项明细
     * (批次号/数量/单价/金额/回溯深度), 复用 {@link OrderCostBreakdownDTO#getSources()} 的形状 ——
     * ⛔ 不新造一份 DTO(本仓形态 D: 同一个东西有两份必定漂)。
     *
     * <p>此前 {@code recalculateSettledPlanCosts} 的响应只有汇总数
     * ({@code authoritativeTotalCost})，审阅者验证不了它是不是把同一个物料批次算了两遍 ——
     * prod 实测冰水批次 {@code 4cce21c0} 在归集里出现两次(traceCost 沿谱系回溯一次、结单并入又算
     * 一次)时，仅凭汇总数完全看不出来；只有把逐条 {@code SourceCost}(含 {@code depth}) 摆出来，
     * 才能看到「同一个 batchName 出现两次、depth 还不一样」这个信号。
     */
    private List<OrderCostBreakdownDTO.SourceCost> costContributions;

    /**
     * 🔴 客供主料成本口径 (a)（owner 裁定，2026-09-04）：本次重算里按加工口径显式记 0 的客供
     * ({@code CUSTOMER_OWNED}) 主料批次（{@code CUSTOMER_SUPPLIED:批次号} 形态，与
     * {@code OrderCostBreakdownDTO#getCustomerSuppliedItems()} 同一份数据）——让审阅者看得到
     * 「这几项不是缺价，是按口径不计入我方成本」，⛔ 不能只给一句总数看不到明细。
     */
    private List<String> customerSuppliedItems;

    /** 上面那份清单的人话版, 直接可以展示；本轮没有客供料按口径记 0 时为 {@code null}。 */
    private String customerSuppliedNote;
}
