package com.cretas.aims.service.supplier;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.TaxRate;
import com.cretas.aims.entity.enums.TaxTreatment;

import java.math.BigDecimal;

/**
 * 19② —— 供应侧「含税录入 → 未税派生」的<b>唯一</b>换算入口。
 *
 * <h2>为什么是一个类而不是各服务写一遍</h2>
 *
 * <p>供应关系和采购规格两侧要的是同一件事。各写一份 = 形态 D, 而且这个换算在本仓
 * <b>已经有过分叉的前科</b>: 同一条 {@code 未税 = 含税 ÷ (1+税率)} 目前存在两份 ——
 * {@code purchaseOrderTaxInclusivePricing.ts}(4 位) 与 {@code material-types/list.vue}
 * 内联的 {@code preTaxUnitPrice}(2 位), 精度不同, 谁也不知道自己是第二份。
 *
 * <p>⇒ 本类<b>不自己算</b>, 一律委托 {@link TaxRate#preTaxPrice(BigDecimal)}
 * (scale=4, HALF_UP) —— 那是后端的权威实现, 也正好与采购单侧同精度。
 * ⛔ 不要在这里写 {@code divide(...)}。
 *
 * <h2>税率从哪来</h2>
 *
 * <p>物料主数据 {@link RawMaterialType} 的 {@code taxRate} / {@code taxTreatment}。
 * 供应侧<b>没有</b>也不需要自己的税率字段 —— 税率是货物的属性, 不是"谁卖它"的属性。
 */
final class SupplierTaxInclusivePricing {

    private SupplierTaxInclusivePricing() {
    }

    /**
     * 由含税价派生未税价。
     *
     * <p>🔴 <b>含税为 null 时返回 null, 调用方必须据此「什么都不做」而不是写入 null。</b>
     * 这条是存量安全的要害: LIUSHANMEN 有 2 行是「未税有值 / 含税为空」(基准未知),
     * 若把 null 结果写回未税列, 就会把该租户仅有的两个真实价格<b>清零</b> ——
     * 一条静默的数据丢失路径, 而且正好打在唯一有价格的那两行上。
     *
     * @param taxIncluded 人工录入的含税价; 为 null 表示「这次没给」
     * @param material    物料主数据 (提供税率与计税方式); 允许为 null
     * @return 派生出的未税价; <b>返回 null 一律表示「无法派生, 请勿改动既有值」</b>
     */
    static BigDecimal deriveUntaxed(BigDecimal taxIncluded, RawMaterialType material) {
        if (taxIncluded == null || material == null) {
            return null;
        }
        // 免税: 未税 == 含税 (与 material-types 那句「免税时未税价等于采购参考价」同口径)
        if (material.getTaxTreatment() == TaxTreatment.EXEMPT) {
            return taxIncluded;
        }
        TaxRate rate = material.getTaxRate();
        if (rate == null) {
            // 无税率 ⇒ 算不出来。与 ProductTypeServiceImpl.applyTaxConversion 同政策:
            // 含税照存, 未税不推导。⛔ 不许拿含税值直接当未税用 —— 那是断言税率为 0。
            return null;
        }
        return rate.preTaxPrice(taxIncluded);
    }

    /**
     * 这条记录的价格基准是不是「未知」—— 未税有值而含税为空。
     *
     * <p>19② 之前录入的价格没有记录它是含税还是未税, 所以这两个字段的这种组合本身
     * 就是那个信息, ⛔ 不需要额外的 flag 列(多一列就多一个会漂开的东西)。
     *
     * <p>基准未知的价格<b>不参与自动带价</b>(编排 2026-09-01 裁定): 一个我们无法解释的数
     * 悄悄给采购单定价, 正是本次改动要消灭的东西。人工确认基准后自动带价即自愈。
     */
    static boolean hasUnknownBasis(BigDecimal untaxed, BigDecimal taxIncluded) {
        return untaxed != null && taxIncluded == null;
    }
}
