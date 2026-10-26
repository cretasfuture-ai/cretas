package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 质检放行 (缺口 11) —— 「这次产出要不要扣成待检」的唯一判据。
 *
 * <p>所有产出入账点 (App 报工入半成品库 / 逐道录入入半成品库或在制批次 / 四条成品入库路径) 都问这里,
 * ⛔ 不在各入账点各写一遍「产品需检吗」(形态 D: 同一件事两份实现必漂)。
 *
 * <h2>判据</h2>
 * 产出的产品开了「需检」({@link ProductType#getInspectionRequired()}):
 * 半成品形态看 {@link #shouldHoldSemi}, 成品批次看 {@link #shouldHoldFinished};
 * 缺口 13 起, 另加「该批次/计划有未处理 CCP 偏离」(口径见 {@code CcpOpenDeviationLookup})。
 *
 * <h2>状态转移 (纯函数, 便于契约测试)</h2>
 * <ul>
 *   <li>半成品行: 不扣 → 原样 (null 仍是 null = 零回归); 扣 → PENDING, 已判不合格的 REJECTED 不被新产出洗白;</li>
 *   <li>在制批次: 新建时 扣 → INSPECTING, 否则 AVAILABLE (与以前一样);</li>
 *   <li>成品批次: 只把 AVAILABLE 改成 PENDING_INSPECTION —— DEFECTIVE (计划质检失败隔离) 等既有状态不动。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class QcInspectionPolicy {

    private final ProductTypeRepository productTypeRepository;

    /**
     * 缺口 13: 产出所属批次/计划有未处理的 CCP 偏离 ⇒ 不管产品开没开需检都扣成待检。
     * required=false: 只按产品开关判的单测直接 new 本类, 不注入 = 不看偏离。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.service.foodsafety.ccp.CcpOpenDeviationLookup deviationLookup;

    /** 测试/装配用: 显式注入偏离判定。 */
    public void setDeviationLookup(com.cretas.aims.service.foodsafety.ccp.CcpOpenDeviationLookup lookup) {
        this.deviationLookup = lookup;
    }

    private boolean hasOpenDeviation(String factoryId, OutputRef ref) {
        return deviationLookup != null
                && deviationLookup.hasOpen(factoryId, ref.batchNumbers(), ref.planId(), ref.productionBatchId());
    }

    /**
     * 一次产出的身份。
     *
     * @param productTypeId 产出的产品 id (可空)
     * @param productCode   产出的产品编码 (App 半成品行按编码入库, 可空)
     * @param batchNumbers  这次产出所属的报工批次号 (生产批次号 / 工序批次号), 供 CCP 偏离判定
     * @param planId        所属生产计划 (可空)
     * @param productionBatchId 所属生产批次 id (可空; 有值时由策略自己补出批次号与计划)
     */
    public record OutputRef(String productTypeId, String productCode,
                            Collection<String> batchNumbers, String planId, Long productionBatchId) {
        public static OutputRef ofProduct(String productTypeId) {
            return new OutputRef(productTypeId, null, List.of(), null, null);
        }
    }

    /**
     * 半成品形态的产出 (常驻半成品库行 / 逐道录入在制批次) 是否要扣成待检。
     *
     * <p>⚠️ 产品是<b>成品</b> (FINISHED_PRODUCT) 时不按开关扣: 旧数据里有把中间道产出记成批次成品的
     * (App legacy 报工 / 非 Workflow 计划), 按成品的「需检」扣它们, 会让每一道中间产出都要放行一次。
     * 成品的待检在成品入库时扣 ({@link #shouldHoldFinished})。
     */
    public boolean shouldHoldSemi(String factoryId, OutputRef ref) {
        if (factoryId == null || ref == null) {
            return false;
        }
        ProductType pt = resolveProduct(factoryId, ref.productTypeId(), ref.productCode());
        boolean byProduct = pt != null && Boolean.TRUE.equals(pt.getInspectionRequired())
                && !com.cretas.aims.entity.enums.ProductCategory.FINISHED_PRODUCT.equals(pt.getProductCategory());
        return byProduct || hasOpenDeviation(factoryId, ref);
    }

    /** 成品批次入库这一笔产出是否要扣成待检。 */
    public boolean shouldHoldFinished(String factoryId, OutputRef ref) {
        if (factoryId == null || ref == null) {
            return false;
        }
        ProductType pt = resolveProduct(factoryId, ref.productTypeId(), ref.productCode());
        return (pt != null && Boolean.TRUE.equals(pt.getInspectionRequired())) || hasOpenDeviation(factoryId, ref);
    }

    /**
     * 成品入库路径共用: 在调用方原本要写的状态 ({@code baseStatus}, 通常 AVAILABLE) 之上叠加质检扣留。
     * 只会把 AVAILABLE 改成 PENDING_INSPECTION, 其它状态原样返回。
     */
    public String finishedGoodsStatusForOutput(String factoryId, String productTypeId, String planId,
                                               String baseStatus, String... batchNumbers) {
        boolean hold = shouldHoldFinished(factoryId,
                new OutputRef(productTypeId, null, batchNumbers(batchNumbers), planId, null));
        return finishedGoodsStatusAfterOutput(baseStatus, hold);
    }

    /** 产出产品: 按 id 优先, 其次按编码; 都找不到 ⇒ null (= 不需检, 存量行为)。 */
    private ProductType resolveProduct(String factoryId, String productTypeId, String productCode) {
        if (productTypeId != null && !productTypeId.isBlank()) {
            ProductType pt = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId).orElse(null);
            if (pt != null) {
                return pt;
            }
        }
        if (productCode != null && !productCode.isBlank()) {
            return productTypeRepository.findByFactoryIdAndCode(factoryId, productCode).orElse(null);
        }
        return null;
    }

    // ==================== 纯函数状态转移 ====================

    /**
     * 半成品行刚入账 {@code inQty} 之后, 若这笔要扣留, 把它<b>按数量</b>记进待检 (审查高 2):
     * 待检数量 += inQty (封顶为当前余额), 状态 PENDING。已放行的存量不动 —— 可领 = 余额 − 待检数量。
     * 旧数据里残留的整行 REJECTED: 回到 PENDING 且<b>整个余额</b>进待检 (旧的不合格量不被新产出洗成可领, 质检员重判)。
     * 不扣 ⇒ 原样 (null 仍是 null = 零回归)。
     *
     * @return 这一笔入账自己扣成待检的量 (记进待检分笔账 {@link SemiQcHoldLots}, 撤销这一笔时只解除它);
     *         不扣 = 0。旧 REJECTED 行被整行拉回待检的那部分不算这一笔的 (撤销这一笔不会把它放出来)。
     */
    public static java.math.BigDecimal applySemiOutputHold(SemiFinishedInventory sfi, boolean hold,
                                                           java.math.BigDecimal inQty) {
        if (!hold || sfi == null) {
            return java.math.BigDecimal.ZERO;
        }
        // 入账前已在待检的量 (口径唯一: SemiFinishedInventory#pendingInspectionQuantity; 不在待检 = 0)
        java.math.BigDecimal priorView = sfi.pendingInspectionQuantity();
        java.math.BigDecimal prior = priorView == null ? java.math.BigDecimal.ZERO : priorView;
        if (SemiFinishedInventory.InspectionStatus.REJECTED.equals(sfi.getInspectionStatus())) {
            // 旧数据残留的整行不合格: 不能因为来了新产出就把旧的不合格量洗成可领 ⇒ 整个余额都进待检,
            // 由质检员重新判 (判不合格时连同旧量一起转出)。
            prior = sfi.computeBalance().max(java.math.BigDecimal.ZERO);
        }
        java.math.BigDecimal pending = prior.add(inQty == null ? java.math.BigDecimal.ZERO : inQty);
        java.math.BigDecimal balance = sfi.computeBalance();
        if (pending.compareTo(balance) > 0) {
            pending = balance.max(java.math.BigDecimal.ZERO);
        }
        sfi.setInspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING);
        sfi.setInspectionPendingQuantity(pending);
        java.math.BigDecimal own = pending.subtract(prior).max(java.math.BigDecimal.ZERO);
        return inQty == null ? java.math.BigDecimal.ZERO : own.min(inQty.max(java.math.BigDecimal.ZERO));
    }

    /** 新建在制半成品批次的初始状态。 */
    public static MaterialBatchStatus wipBatchInitialStatus(boolean hold) {
        return hold ? MaterialBatchStatus.INSPECTING : MaterialBatchStatus.AVAILABLE;
    }

    /** 在制批次重物化 (数量改了) 之后的状态: 只把可用改回待检, 其它状态不动。 */
    public static MaterialBatchStatus wipBatchStatusAfterOutput(MaterialBatchStatus current, boolean hold) {
        return hold && current == MaterialBatchStatus.AVAILABLE ? MaterialBatchStatus.INSPECTING : current;
    }

    /** 成品批次入一笔生产产出之后的状态。 */
    public static String finishedGoodsStatusAfterOutput(String current, boolean hold) {
        return hold && FinishedGoodsBatch.Status.AVAILABLE.equals(current)
                ? FinishedGoodsBatch.Status.PENDING_INSPECTION
                : current;
    }

    /** 非空且去重的批次号, 给调用方拼 OutputRef 用。 */
    public static List<String> batchNumbers(String... numbers) {
        return java.util.Arrays.stream(numbers)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
