package com.cretas.aims.service.inventory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;

/**
 * 成品(FG)批次跨单位换算 — 销售 FEFO 推荐 / 分配校验 / 发货扣减 三段共用。
 *
 * <p><b>背景 (2026-07 F006 现场确认, 🔴 C1)</b>: 同一 {@code productTypeId} 下的成品批次可能记录在
 * <b>不同单位</b> —— 小结时填了「本批实收重量」的批次 unit=kg, 未填的批次沿用计数单位(盒/个/件)。
 * 例: 同产品一批 0.45kg, 另一批 4454.5盒。销售 FEFO 推荐 / 分配校验 / 发货扣减 之前把不同批次的
 * {@code availableQuantity} 当作与发货行单位直接可比/可加总的裸数字处理 —— kg 与 盒 数量级完全不同,
 * 直接相加/相减/比较全部算错。
 *
 * <p>换算依据内置 {@code CanonicalUnit} 的量纲/因子；质量与计数/包装之间另依据
 * {@link com.cretas.aims.entity.ProductType#getGramsPerUnit()} (每盒/份标准克重),
 * 与 {@link FeedUnitConverter}（逐道投料折算）/
 * {@link com.cretas.aims.service.restock.RestockUnitConverter}（备货看板）同一口径
 * (盒 = kg × 1000 / gramsPerUnit)。
 *
 * <p>🔴 <b>诚实 null (禁止降级)</b>: 单位不同且无法确定换算系数 (缺 gramsPerUnit, 或双方都是计数单位
 * 但字符串不同, 如 "盒" vs "个") → 返回 {@code null}。调用方必须据此 loud-fail / 跳过该批,
 * <b>绝不能把 null 当 0 或裸相加</b>。
 */
public final class FgQuantityUnitConverter {

    private FgQuantityUnitConverter() {}

    /** Keep fractional native quantities without silently truncating grams or package quantities. */
    private static final MathContext CONVERSION_CONTEXT = MathContext.DECIMAL128;

    /**
     * 把数量 {@code qty}（原单位 {@code fromUnit}）换算为 {@code toUnit} 计量。
     *
     * <ul>
     *   <li>{@code fromUnit} 与 {@code toUnit} 字符串相等（含都为 null）→ 原样返回，无需换算。</li>
 *   <li>同一科学量纲 (如 g/kg、ml/l) → 按内置 canonical factor 换算。</li>
 *   <li>质量与计数/包装单位之间 → 经 {@code gramsPerUnit} 换算；体积与计数/包装之间拒绝换算。</li>
 *   <li>双方都是不同的计数/包装单位，或任一单位不在内置契约中 → 返回 {@code null}
 *       (诚实失败，不臆造 1:1)。</li>
     *   <li>需要换算但 {@code gramsPerUnit} 缺失/非正 → 返回 {@code null}。</li>
     * </ul>
     *
     * @param qty          待换算数量 (null 直接返回 null)
     * @param fromUnit     qty 的原单位
     * @param toUnit       目标单位
     * @param gramsPerUnit 该产品每盒/份标准克重 ({@link com.cretas.aims.entity.ProductType#getGramsPerUnit()})
     * @return 换算后数量；无法换算时返回 {@code null}
     */
    public static BigDecimal convert(BigDecimal qty, String fromUnit, String toUnit, BigDecimal gramsPerUnit) {
        if (qty == null) {
            return null;
        }
        if (java.util.Objects.equals(fromUnit, toUnit)) {
            return qty;
        }
        if (fromUnit == null || toUnit == null) {
            // 一方单位未知 → 无法确定是否需要换算, 诚实失败.
            return null;
        }
        Optional<CanonicalUnit> from = UnitContractServiceImpl.describeBuiltIn(fromUnit);
        Optional<CanonicalUnit> to = UnitContractServiceImpl.describeBuiltIn(toUnit);
        if (from.isEmpty() || to.isEmpty()) {
            return null;
        }

        CanonicalUnit source = from.get();
        CanonicalUnit target = to.get();
        if (source.code().equals(target.code())) {
            return qty;
        }

        boolean sourceCountLike = isCountLike(source);
        boolean targetCountLike = isCountLike(target);
        if (sourceCountLike || targetCountLike) {
            if (sourceCountLike && targetCountLike) {
                // Distinct count/package units have no implicit 1:1 relationship.
                return null;
            }
            if ((sourceCountLike && requiresPackagingSnapshot(source)
                    && target.dimension() == UnitDimension.MASS)
                    || (targetCountLike && requiresPackagingSnapshot(target)
                    && source.dimension() == UnitDimension.MASS)) {
                // An outer case/carton has no intrinsic net weight; expand it via
                // the recorded packaging snapshot before applying gramsPerUnit.
                return null;
            }
            if (gramsPerUnit == null || gramsPerUnit.signum() <= 0) {
                return null;
            }
            if (source.dimension() != UnitDimension.MASS && !sourceCountLike
                    || target.dimension() != UnitDimension.MASS && !targetCountLike) {
                // In particular, volume ↔ package/count must never borrow SKU mass.
                return null;
            }
            if (sourceCountLike && target.dimension() == UnitDimension.MASS
                    && target.factorToBase() != null) {
                return qty.multiply(gramsPerUnit, CONVERSION_CONTEXT)
                        .divide(target.factorToBase(), CONVERSION_CONTEXT);
            }
            if (source.dimension() == UnitDimension.MASS && source.factorToBase() != null
                    && targetCountLike) {
                return qty.multiply(source.factorToBase(), CONVERSION_CONTEXT)
                        .divide(gramsPerUnit, CONVERSION_CONTEXT);
            }
            return null;
        }

        if (source.dimension() != target.dimension()
                || source.factorToBase() == null || target.factorToBase() == null
                || (source.dimension() != UnitDimension.MASS
                    && source.dimension() != UnitDimension.VOLUME
                    && source.dimension() != UnitDimension.LENGTH)) {
            return null;
        }
        return qty.multiply(source.factorToBase(), CONVERSION_CONTEXT)
                .divide(target.factorToBase(), CONVERSION_CONTEXT);
    }

    private static boolean isCountLike(CanonicalUnit unit) {
        return unit.dimension() == UnitDimension.COUNT || unit.dimension() == UnitDimension.PACKAGE;
    }

    private static boolean requiresPackagingSnapshot(CanonicalUnit unit) {
        return unit.dimension() == UnitDimension.PACKAGE && "箱".equals(unit.code());
    }

    /**
     * Converts through optional per-transaction packaging snapshots before applying the existing
     * count/weight conversion. This keeps two carton choices for the same SKU unambiguous:
     * source cartons are expanded with the source snapshot, while target cartons are collapsed
     * with the target snapshot.
     */
    public static BigDecimal convertWithPackaging(
            BigDecimal qty,
            String fromUnit,
            String toUnit,
            BigDecimal gramsPerUnit,
            String fromPackagingUnit,
            String fromPackagingBaseUnit,
            BigDecimal fromPackagingFactor,
            String toPackagingUnit,
            String toPackagingBaseUnit,
            BigDecimal toPackagingFactor) {
        if (qty == null) return null;

        BigDecimal workingQty = qty;
        String workingFrom = fromUnit;
        if (isPackagingMatch(fromUnit, fromPackagingUnit, fromPackagingBaseUnit, fromPackagingFactor)) {
            workingQty = workingQty.multiply(fromPackagingFactor);
            workingFrom = fromPackagingBaseUnit;
        }

        boolean targetIsPackaging = isPackagingMatch(
                toUnit, toPackagingUnit, toPackagingBaseUnit, toPackagingFactor);
        String workingTo = targetIsPackaging ? toPackagingBaseUnit : toUnit;
        BigDecimal converted = convert(workingQty, workingFrom, workingTo, gramsPerUnit);
        if (converted == null) return null;
        if (!targetIsPackaging) return converted;
        return converted.divide(toPackagingFactor, CONVERSION_CONTEXT);
    }

    private static boolean isPackagingMatch(
            String actualUnit, String packagingUnit, String baseUnit, BigDecimal factor) {
        return actualUnit != null
                && sameBuiltInUnit(actualUnit, packagingUnit)
                && baseUnit != null
                && !baseUnit.isBlank()
                && factor != null
                && factor.signum() > 0;
    }

    private static boolean sameBuiltInUnit(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        Optional<CanonicalUnit> leftUnit = UnitContractServiceImpl.describeBuiltIn(left);
        Optional<CanonicalUnit> rightUnit = UnitContractServiceImpl.describeBuiltIn(right);
        return leftUnit.isPresent() && rightUnit.isPresent()
                && leftUnit.get().code().equals(rightUnit.get().code());
    }
}
