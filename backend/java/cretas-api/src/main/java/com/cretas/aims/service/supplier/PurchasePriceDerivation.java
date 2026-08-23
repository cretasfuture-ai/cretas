package com.cretas.aims.service.supplier;

import java.math.BigDecimal;

/**
 * 「供应关系采购价 → 某个采购包装规格的计价」唯一一处推导。
 *
 * <p>客户 2026-07-30 表格第 38 行:「在成品SKU中设定完换算规格后, 生成采购订单时不再自动填入
 * 供应商中设定好的采购单价」。成因是配了规格之后计价单位变成「箱」, 而供应关系上的价是「元/kg」,
 * 前端 {@code applyRelationPrice} 按单位字符串严格相等匹配, 两边对不上就把价清空。
 *
 * <p>#2004/{@code d5c3b2de88} 已经给**供应商侧**采购规格
 * ({@code SupplierMaterialPurchaseSpec}) 修好了, 但**物料侧**包装换算
 * ({@code MaterialPackagingSpec}, 在「原料档案 → 包装换算」里配) 走的是另一条读取路径,
 * 那条路径上的 DTO 一个价格字段都没有 ⇒ 同一个症状在「供应商没配采购规格、只配了物料包装换算」
 * 时原样复现。
 *
 * <p>⚠️ 两条路径必须用**同一份**推导, 否则一定漂 —— 所以抽到这里, 两边都只调它。
 * ⛔ 前端不许自己乘换算系数: 见 {@code web-admin/src/utils/unitPricing.ts} 的
 * 「单位不同且没有后端换算结果时必须 fail closed, 绝不猜 conversionFactor」。
 */
public final class PurchasePriceDerivation {

    private PurchasePriceDerivation() {
    }

    /** 推导结果; 推不出来时返回 {@code null}, ⛔ 不返回 0 (0 会被下游当成一个真实报价)。 */
    public record Derived(BigDecimal price, String unit, String source) {
    }

    /** 关系价单位与规格包装单位相同 —— 原样带过, <b>不能再乘一次系数</b>。 */
    public static final String SOURCE_SAME_UNIT = "SUPPLIER_RELATION_SAME_UNIT";
    /** 关系价按库存基本单位计 —— 乘本规格自己写的「1 包装 = N 基本单位」。 */
    public static final String SOURCE_CONVERTED = "SUPPLIER_RELATION_CONVERTED";

    /**
     * 刻意只认两种情形, 跨不过去就返回 null:
     * <ul>
     *   <li>关系价单位 == 规格包装单位 → 原样</li>
     *   <li>关系价单位 == 规格库存基本单位 → 价 × 本规格 conversionFactor</li>
     * </ul>
     * 不引入通用单位引擎: 换算依据必须是这一行自己写着的「1 箱 = N kg」, 否则就是替用户猜价格。
     *
     * @param relationPrice 供应关系上的采购价 (元 / relationUnit)
     * @param relationUnit  该价格的计价单位
     * @param packageUnit   规格的采购包装单位 (箱/件…)
     * @param baseUnit      规格的库存基本单位 (kg/个…)
     * @param factor        1 packageUnit = factor × baseUnit
     */
    public static Derived derive(BigDecimal relationPrice, String relationUnit,
                                 String packageUnit, String baseUnit, BigDecimal factor) {
        if (relationPrice == null || relationPrice.compareTo(BigDecimal.ZERO) <= 0) return null;
        String rel = normalize(relationUnit);
        String pkg = normalize(packageUnit);
        String base = normalize(baseUnit);
        if (rel == null || pkg == null) return null;

        if (rel.equals(pkg)) {
            return new Derived(relationPrice, packageUnit, SOURCE_SAME_UNIT);
        }
        if (base != null && base.equals(rel) && factor != null && factor.compareTo(BigDecimal.ZERO) > 0) {
            return new Derived(relationPrice.multiply(factor), packageUnit, SOURCE_CONVERTED);
        }
        return null;
    }

    private static String normalize(String unit) {
        if (unit == null) return null;
        String trimmed = unit.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }
}
