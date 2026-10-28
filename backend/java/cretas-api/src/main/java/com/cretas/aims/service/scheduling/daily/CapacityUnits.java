package com.cretas.aims.service.scheduling.daily;

import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import java.util.Optional;

/**
 * 排程里「这个量能不能换成计划单位」的唯一判据。
 *
 * <ul>
 *   <li>同一个单位的不同写法 (袋 ≡ bag, kg ≡ 公斤): 系数 1 ({@link UnitContractServiceImpl#crossLanguageCode},
 *       只 ≠ 件 / 个 ≠ 件 守住)。</li>
 *   <li>有恒定换算的同量纲内置单位 (kg ↔ g ↔ 斤 ↔ 吨, l ↔ ml): 按内置系数换。</li>
 *   <li>其余 (袋 ↔ kg, 盒 ↔ 袋, 租户自定义单位): 换算不了 —— ⛔ 不按每袋克重之类去猜。</li>
 * </ul>
 */
public final class CapacityUnits {

    private CapacityUnits() {
    }

    /** 把 {@code from} 单位的量乘以返回的系数得到 {@code to} 单位的量; 换算不了返回空。 */
    public static Optional<BigDecimal> factor(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return Optional.empty();
        }
        if (Objects.equals(UnitContractServiceImpl.crossLanguageCode(from),
                UnitContractServiceImpl.crossLanguageCode(to))) {
            return Optional.of(BigDecimal.ONE);
        }
        CanonicalUnit a = UnitContractServiceImpl.describeBuiltIn(from.trim()).orElse(null);
        CanonicalUnit b = UnitContractServiceImpl.describeBuiltIn(to.trim()).orElse(null);
        if (a == null || b == null || a.factorToBase() == null || b.factorToBase() == null) {
            return Optional.empty();
        }
        if (a.dimension() != b.dimension()
                || (a.dimension() != UnitDimension.MASS && a.dimension() != UnitDimension.VOLUME)
                || !Objects.equals(a.baseCode(), b.baseCode())) {
            return Optional.empty();
        }
        return Optional.of(a.factorToBase().divide(b.factorToBase(), MathContext.DECIMAL64));
    }

    public static Optional<BigDecimal> convert(BigDecimal quantity, String from, String to) {
        if (quantity == null) {
            return Optional.empty();
        }
        return factor(from, to).map(quantity::multiply);
    }
}
