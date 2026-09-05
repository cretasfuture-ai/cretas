package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomSeasoningItem;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 「辅料/调料按 BOM 配比折算」的<b>唯一</b>定义 —— 配比、计价、质量单位折算三件事各只有一份。
 *
 * <h2>为什么抽出来 (本仓形态 D / 硬约束 8)</h2>
 *
 * <p>2026-09-06 做「辅料先估」(结单成本口径) 时发现同一条公式已经在两处各写了一份:</p>
 * <ul>
 *   <li>后端领料单 {@code FactoryMaterialRequisitionServiceImpl#seasoningRequiredQty}
 *       (#460/#483) —— {@code dosagePerKgG × 投入kg ÷ 1000}</li>
 *   <li>前端确认领料实时预览 {@code web-admin/.../confirmPickingAuxDerive.ts#deriveAuxiliaryQty}
 *       —— 同一条公式的 TS 版 (跨语言, 抽不成一份, 已在该文件顶部登记为「两侧各自独立实现」)</li>
 * </ul>
 * 成本侧再写第三份就是第三个会漂的地方。⇒ Java 侧收敛到本类, 领料单改为委托;
 * TS 那份保持独立(跨运行时抽不动)，但公式逐字相同。
 *
 * <h2>口径 (⛔ 改这里等于同时改领料单和结单成本, 先读完再动)</h2>
 * <ol>
 *   <li><b>配比</b> {@link BomSeasoningItem#getDosagePerKgG()} 的量纲是
 *       <b>g 辅料 / kg 投入主料</b> —— 「单锅基准」, ⛔ 不含 {@code subsequentPotRatio}
 *       那一层放大 (计划期不知道分几锅; 报工时按真实锅次重算才是权威值)。</li>
 *   <li><b>计价</b> {@link BomSeasoningItem#getPriceSource1()} 优先, 否则
 *       {@link BomSeasoningItem#getPriceSource2()}, 都没有(或 ≤0) 就是<b>没有价</b> ——
 *       与 {@code BomRecipeServiceImpl#seasoningCost} 逐字同规则。
 *       <p>🔴 <b>它的量纲是 ¥/kg</b>, 不是 ¥/g: {@code BomRecipeServiceImpl} 算的是
 *       {@code (dosagePerKgG ÷ 1000) × price}, 即「kg 辅料 × price」⇒ price 必须是 ¥/kg。
 *       生产库实测(2026-09-06)与之一致: 27/27 个被 BOM 引用的辅料, 其
 *       {@code raw_material_types.unit} 全是 {@code kg}; 且 F006 的 {@code price_source2}
 *       与 {@code raw_material_types.unit_price} <b>逐行相等</b>(生姜粉 122.53 / 盐 0.89 / 香辛料 8.85)。
 *       ⇒ 本类不做任何价格侧单位换算, 因为两侧的单位都是<b>由定义钉死</b>的; 需要换算的只有
 *       下面那个「投入主料量 → kg」。</li>
 *   <li><b>质量折算</b> {@link #massToKg} 只认质量单位。计数单位(个/只/件…)与 kg
 *       之间<b>没有通用换算</b>, 硬折就是编数 ⇒ 返回 {@code null}, 由调用方决定怎么办
 *       (⛔ 不返回 0 —— 0 会被下游读成「一粒都没有」, 本仓形态 A¹⁰)。</li>
 * </ol>
 *
 * <p>⚠️ {@link #massToKg} 刻意<b>不</b>走 {@code UnitContractServiceImpl.crossLanguageCode}:
 * 那会把它认得的更多写法一并放进来, 于是领料单会开始折算它此前明确拒绝折算的单位 ——
 * 那是行为变更, 不是重构。这里逐字保留领料单原有的字面量集合, 使「领料单委托本类」
 * 是可证明的零行为变更。要扩单位集合是另一件事, 单独做。
 *
 * @since 2026-09-06
 */
public final class SeasoningRatioContract {

    private SeasoningRatioContract() {
    }

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    /**
     * 这条 BOM 调料行<b>算不算进调料成本</b>。
     *
     * <p>{@code COOKING} 段且 {@code countInSeasoning=false} 的不计入 (老汤/高汤那一类:
     * 它是循环使用的介质, 不是本批被消耗掉的辅料)。与报工侧、领料单侧同口径。
     *
     * <p>⚠️ 硬约束 8 显式登记「故意不改」: {@code BomRecipeServiceImpl#seasoningCost} 用的是
     * <b>更严</b>的一条 —— {@code !Boolean.TRUE.equals(countInSeasoning)} 就跳过, 不看 section。
     * 两者只在「{@code INJECTION} 段 + {@code countInSeasoning=false}」这一种组合上分歧, 而
     * 生产库实测(2026-09-06) {@code bom_seasoning_items} 134 行<b>全部是 COOKING 段</b>,
     * 该组合当前一行都不存在 ⇒ 两条规则在存量上等价。把 BOM 设计期成本(那是另一条产品线的口径)
     * 一并改掉不属于本次范围, 故本类采纳成本/领料侧那条, 并在此登记分歧。
     */
    public static boolean countsTowardSeasoningCost(BomSeasoningItem item) {
        if (item == null) {
            return false;
        }
        return !(BomSeasoningItem.SECTION_COOKING.equals(item.getSection())
                && !Boolean.TRUE.equals(item.getCountInSeasoning()));
    }

    /** 这条行有没有可用配比 —— {@code dosagePerKgG} 非空且 &gt; 0。0 不是「不放」, 是没填。 */
    public static boolean hasUsableRatio(BomSeasoningItem item) {
        return item != null && item.getDosagePerKgG() != null && item.getDosagePerKgG().signum() > 0;
    }

    /**
     * 按配比折算出的辅料需求量 (<b>g</b>, 未做任何舍入)。
     *
     * <p>{@code = dosagePerKgG × rawInputKg}。调用方自己决定换成什么单位、舍到几位 ——
     * 领料单要 3 位 (给拣货员看), 成本侧要更高精度 (要参与金额计算)。
     *
     * @return 算不出时 {@code null} (⛔ 不是 0): 行为空 / 不计入调料 / 无配比 /
     *         投入主料量未知或非正数。
     */
    public static BigDecimal requiredGrams(BomSeasoningItem item, BigDecimal rawInputKg) {
        if (item == null || rawInputKg == null || rawInputKg.signum() <= 0) {
            return null;
        }
        if (!countsTowardSeasoningCost(item) || !hasUsableRatio(item)) {
            return null;
        }
        return item.getDosagePerKgG().multiply(rawInputKg);
    }

    /**
     * 这条 BOM 调料行的单价 (<b>¥/kg</b>, 见类注释口径②)。
     *
     * @return 两个来源都没有可用值时 {@code null} —— ⛔ 调用方不许兜底成 0
     *         (「没填价」被当成「不要钱」会系统性低估 COGS、虚增毛利)
     */
    public static BigDecimal unitPricePerKg(BomSeasoningItem item) {
        if (item == null) {
            return null;
        }
        if (item.getPriceSource1() != null && item.getPriceSource1().signum() > 0) {
            return item.getPriceSource1();
        }
        if (item.getPriceSource2() != null && item.getPriceSource2().signum() > 0) {
            return item.getPriceSource2();
        }
        return null;
    }

    /** {@link #unitPricePerKg} 取到的是哪一个来源 —— 读数必须带来源(本仓形态 A¹³)。 */
    public static String unitPriceSource(BomSeasoningItem item) {
        if (item == null) {
            return null;
        }
        if (item.getPriceSource1() != null && item.getPriceSource1().signum() > 0) {
            return "BOM_SEASONING_PRICE_SOURCE1";
        }
        if (item.getPriceSource2() != null && item.getPriceSource2().signum() > 0) {
            return "BOM_SEASONING_PRICE_SOURCE2";
        }
        return null;
    }

    /**
     * 质量单位 → kg。⛔ 不是质量单位就返回 {@code null}, 不猜。
     *
     * <p>逐字搬自 {@code FactoryMaterialRequisitionServiceImpl#massToKg}(含各分支的 scale),
     * 使那边改为委托是可证明的零行为变更。
     */
    public static BigDecimal massToKg(BigDecimal qty, String unit) {
        if (qty == null || unit == null) {
            return null;
        }
        String u = unit.trim();
        if ("kg".equalsIgnoreCase(u) || "千克".equals(u) || "公斤".equals(u)) {
            return qty;
        }
        if ("g".equalsIgnoreCase(u) || "克".equals(u)) {
            return qty.divide(THOUSAND, 6, RoundingMode.HALF_UP);
        }
        if ("t".equalsIgnoreCase(u) || "吨".equals(u)) {
            return qty.multiply(THOUSAND);
        }
        if ("jin".equalsIgnoreCase(u) || "斤".equals(u)) {
            return qty.divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP);
        }
        return null;
    }
}
