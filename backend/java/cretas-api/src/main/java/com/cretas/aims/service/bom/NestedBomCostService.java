package com.cretas.aims.service.bom;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.shared.CostRollupUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * SP1: 嵌套 BOM 成本聚合服务。
 *
 * <h2>两类成本来源</h2>
 * <ol>
 *   <li><b>设计时成本 (递归 BOM)</b>: 当 {@link BomRecipeItem#getSubProductTypeId()} 非 null 时,
 *       查找该子产品的当前有效 BOM ({@code status=ACTIVE, is_current=TRUE}),
 *       取其 {@code totalCost / outputQuantityPerUnit} 得到单位成本, 再乘以本行实际用量。
 *       支持多层嵌套 (含循环检测, 最大 10 层)。</li>
 *   <li><b>运行时成本 (先做后用)</b>: 当 {@link BomRecipeItem#getSemiFinishedRefCode()} 非 null 时,
 *       查找 {@link SemiFinishedInventory} 的 {@code unitCost} (移动均价), 优先于 BOM 设计成本。
 *       此场景适用于"先生产半成品入库, 后续成品领用"。</li>
 * </ol>
 *
 * <h2>降级规则 (诚实 null 传播)</h2>
 * <ul>
 *   <li>子 BOM 不存在或 {@code totalCost} 为 null → 返 null (不伪造成本)。</li>
 *   <li>WIP unitCost 为 null → 降级到子 BOM 设计成本; 若亦 null → 返 null。</li>
 *   <li>循环 BOM 检测: 同路径出现已访问的 productTypeId → 返 null (切断递归), 并 WARN 日志。</li>
 * </ul>
 *
 * @author Cretas Team
 * @since 2026-06-11 (SP1 P1 nested BOM cost aggregation)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NestedBomCostService {

    /** 防御性递归深度上限, 防栈溢出 (实际生产 BOM 极少超过 5 层). */
    private static final int MAX_DEPTH = 10;

    private final BomRecipeRepository bomRecipeRepository;
    private final SemiFinishedInventoryRepository sfiRepository;

    /**
     * 解析单个 BOM 行的成本, 处理嵌套子产品和"先做后用" WIP 成本。
     *
     * <p>若 {@code item.subProductTypeId} 为 null, 直接返回 {@code item.computeItemCost()}
     * (即现有的 {@code actualQuantity × unitPrice} 逻辑, 不破坏单层 BOM 行为)。
     *
     * @param factoryId 工厂 ID
     * @param item      BOM 配方行
     * @return 本行成本 (BigDecimal, HALF_UP scale 4); null 表示成本未知 (诚实传播)
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveItemCost(String factoryId, BomRecipeItem item) {
        boolean hasSubProduct = item.getSubProductTypeId() != null
                && !item.getSubProductTypeId().isBlank();
        boolean hasSemiRef = BomRecipeItem.isSemiFinishedRow(item);
        if (!hasSubProduct && !hasSemiRef) {
            // 普通原材料行 — 走现有逻辑
            return item.computeItemCost();
        }

        BigDecimal actualQty = item.calculateActualQuantity();
        if (actualQty == null || actualQty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // 1. "先做后用"优先: 若有 semiFinishedRefCode, 尝试从 WIP 取移动均价
        //
        // 🔴 2026-08-29: 这一段以前**够不到** —— 方法开头是
        //     if (subProductTypeId == null) return item.computeItemCost();
        //    也就是说只填了 semiFinishedRefCode 的行(回料行的正常长相)一进来就被弹回
        //    computeItemCost(), 而那条路算的是 actualQuantity × unitPrice, 回料行没有
        //    原料档案也就没有 unitPrice ⇒ 恒 null ⇒ 整个 family 成本被判「不完整」。
        //    机制在、注释在、单测在, 只是生产上没人能走到 (形态 B)。
        if (hasSemiRef) {
            Optional<SemiFinishedInventory> wip = sfiRepository
                    .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
                            factoryId, item.getSemiFinishedRefCode());
            if (wip.isPresent() && wip.get().getUnitCost() != null) {
                BigDecimal unitCost = wip.get().getUnitCost();
                log.debug("SP1 先做后用: item={} semiFinishedRefCode={} unitCost={}",
                        item.getId(), item.getSemiFinishedRefCode(), unitCost);
                return CostRollupUtil.calcItemCost(actualQty, unitCost);
            }
            // WIP unitCost null or WIP not found → 降级到子 BOM 设计成本
            log.debug("SP1 先做后用降级: semiFinishedRefCode={} WIP unitCost 为 null 或 WIP 不存在, 降级到子 BOM 设计成本",
                    item.getSemiFinishedRefCode());
        }

        if (!hasSubProduct) {
            // 回料行没有绑子产品 ⇒ 没有第二条成本来源。诚实返 null (⛔ 不伪造 0):
            // 「不知道多少钱」和「不要钱」对下游完全不同 —— 后者会让标准成本看起来完整。
            log.warn("半成品(回料)行成本未知: factoryId={}, itemId={}, semiFinishedRefCode={} "
                            + "(WIP 无移动均价, 且未绑 subProductTypeId 可回退子 BOM 设计成本)",
                    factoryId, item.getId(), item.getSemiFinishedRefCode());
            return null;
        }

        // 2. 递归子 BOM 设计成本
        Set<String> visited = new HashSet<>();
        BigDecimal subUnitCost = resolveSubBomUnitCost(factoryId, item.getSubProductTypeId(), visited, 0);
        if (subUnitCost == null) {
            // Q3: 附 itemId 便于定位是哪一 BOM 行的嵌套成本无法解析 (深度/环/未定价已在子方法 warn).
            log.warn("SP1 嵌套成本返 null: factoryId={}, itemId={}, subProductTypeId={} (子 BOM 未配置/未定价/超深/成环)",
                    factoryId, item.getId(), item.getSubProductTypeId());
            return null;
        }
        return CostRollupUtil.calcItemCost(actualQty, subUnitCost);
    }

    /**
     * 递归解析子产品 BOM 的单位成本 (= totalCost / outputQuantityPerUnit)。
     *
     * @param factoryId       工厂 ID
     * @param productTypeId   子产品 ID
     * @param visited         当前 DFS 路径已访问集合 (循环检测)
     * @param depth           当前递归深度
     * @return 子产品单位成本; null 表示成本未知
     */
    private BigDecimal resolveSubBomUnitCost(String factoryId,
                                              String productTypeId,
                                              Set<String> visited,
                                              int depth) {
        if (depth >= MAX_DEPTH) {
            // Q3: 诚实告警 — 深度超限返 null 不静默, 附 factoryId/depth/路径 便于排查.
            log.warn("SP1 嵌套 BOM 深度超限 (≥{}), 成本返 null: factoryId={}, productTypeId={}, depth={}, visited={}",
                    MAX_DEPTH, factoryId, productTypeId, depth, visited);
            return null;
        }

        if (visited.contains(productTypeId)) {
            // Q3: 诚实告警 — 检测到环返 null 不静默, 附 factoryId/depth/环路径 便于排查.
            log.warn("SP1 嵌套 BOM 循环检测, 成本返 null: factoryId={}, productTypeId={} 已在路径 {} 中 (depth={}), 切断递归",
                    factoryId, productTypeId, visited, depth);
            return null;
        }
        visited.add(productTypeId);

        Optional<BomRecipe> subRecipeOpt = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId);
        if (subRecipeOpt.isEmpty()) {
            log.debug("SP1 嵌套 BOM: productTypeId={} 无当前有效 BOM, 成本 null", productTypeId);
            visited.remove(productTypeId);
            return null;
        }

        BomRecipe subRecipe = subRecipeOpt.get();
        BigDecimal totalCost = subRecipe.getTotalCost();

        if (totalCost == null) {
            // 子 BOM 有未定价原料 → 整体成本 null (诚实传播)
            log.debug("SP1 嵌套 BOM: subRecipe={} productTypeId={} totalCost 为 null (含未定价原料)",
                    subRecipe.getId(), productTypeId);
            visited.remove(productTypeId);
            return null;
        }

        BigDecimal outputQtyPerUnit = subRecipe.getOutputQuantityPerUnit();
        if (outputQtyPerUnit == null || outputQtyPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("SP1 嵌套 BOM: subRecipe={} outputQuantityPerUnit={} 非正, 无法计算单位成本",
                    subRecipe.getId(), outputQtyPerUnit);
            visited.remove(productTypeId);
            return null;
        }

        // 单位成本 = totalCost / outputQuantityPerUnit, scale 4 HALF_UP
        BigDecimal unitCost = totalCost.divide(outputQtyPerUnit, CostRollupUtil.COST_SCALE, RoundingMode.HALF_UP);
        log.debug("SP1 嵌套 BOM: productTypeId={} totalCost={} / outputQtyPerUnit={} = unitCost={}",
                productTypeId, totalCost, outputQtyPerUnit, unitCost);

        visited.remove(productTypeId);
        return unitCost;
    }

    /**
     * 判断某 BOM 行是否需要走 {@link #resolveItemCost} (而不是 {@code item.computeItemCost()})。
     *
     * <p>🔴 2026-08-29 修正: 以前只认 {@code subProductTypeId}, 于是**只填
     * {@code semiFinishedRefCode} 的回料行**在两个调用点
     * ({@code BomRecipeServiceImpl.recomputeFamilyCosts} / {@code sumItemCosts})
     * 都被判成「普通行」→ 走 {@code actualQuantity × unitPrice} → 回料行没有原料档案
     * 也就没有 unitPrice → 恒 null。「先做后用」那段代码因此一次都没被生产调用过。
     *
     * @param item BOM 配方行
     * @return true 当且仅当 {@code subProductTypeId} 或 {@code semiFinishedRefCode} 非 null/blank
     */
    public boolean isNestedComponent(BomRecipeItem item) {
        return (item.getSubProductTypeId() != null && !item.getSubProductTypeId().isBlank())
                || BomRecipeItem.isSemiFinishedRow(item);
    }
}
