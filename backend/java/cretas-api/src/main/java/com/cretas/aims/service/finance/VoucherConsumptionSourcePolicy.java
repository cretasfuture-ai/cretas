package com.cretas.aims.service.finance;

import java.util.Map;
import java.util.Set;

/**
 * 哪些 {@code material_consumptions} 行进财务凭证 —— <b>唯一判据</b>。
 *
 * <h4>产品裁定（2026-08-27，Owner）</h4>
 *
 * <p>「<b>消耗行不进凭证，只有生产领用进</b>」。</p>
 *
 * <h4>此前是什么样</h4>
 *
 * <p>{@code VoucherExportServiceImpl} 的写法是
 * {@code for (c : materialConsumptionRepo.findByTimeRange(factoryId, start, end))} ——
 * <b>不按来源过滤，窗口内每一行都记成「原料发出」</b>。</p>
 *
 * <p>🔴 生产实测（2026-08-27，全库）暴露出两个后果：</p>
 *
 * <pre>
 *   RAW_MATERIAL     83 行   5538.36     ← 生产领用
 *   PACKAGING        52 行     26.11     ← 生产领用
 *   SEASONING        46 行    167.52     ← 生产领用
 *   MATERIAL_RETURN  23 行   -631.40     ← 生产退料(负数, 是领用的冲减)
 *   TRANSFER_OUT     23 行   1969.00     ← 🔴 移库, 不是消耗, 却被记成「原料发出」
 *   SEMI_FINISHED    17 行    777.87     ← 生产领用
 *   MANUAL_ISSUE      1 行      0.00     ← 手工出库
 * </pre>
 *
 * <p>⇒ {@code TRANSFER_OUT} 那 1969 元是仓库之间搬货，货还在厂里，
 * 记成消耗等于凭空多出一笔成本。</p>
 *
 * <h4>⚠️ 为什么 MATERIAL_RETURN 必须进</h4>
 *
 * <p>它的金额是<b>负的</b>——它是生产领用的<b>冲减</b>，不是另一件事。
 * 领用进而退料不进，成本会虚高（实测这一项 −631.40）。
 * ⛔ 「只有生产领用进」这句话里的「生产领用」是一个<b>会计科目</b>，不是一个动作名。</p>
 *
 * <h4>未知来源怎么办</h4>
 *
 * <p>财务输出上，<b>静默计入</b>和<b>静默排除</b>都是错的：前者凭空造成本，
 * 后者悄悄少算。⇒ 两手：</p>
 *
 * <ul>
 *   <li><b>运行时</b>：未知来源<b>不计入</b>，但把条数与来源名<b>打进日志</b>，不静默。</li>
 *   <li><b>CI</b>：{@code VoucherConsumptionSourceCoverageContractTest} 扫全 {@code src/main}
 *       里所有 {@code setSourceType} 的字面量，每一个都必须在下面两张表之一里 ——
 *       <b>新增一种来源会让 CI 红，而不是让某个月的凭证悄悄变数</b>。</li>
 * </ul>
 */
public final class VoucherConsumptionSourcePolicy {

    /** 进凭证：这些是生产领用（含它的冲减）。 */
    public static final Set<String> IN_VOUCHER = Set.of(
            "RAW_MATERIAL",      // 报工领用 - 原料
            "PACKAGING",         // 报工领用 - 包材
            "SEASONING",         // 报工领用 - 辅料/调味
            "SEMI_FINISHED",     // 报工领用 - 半成品投料
            "PRODUCTION_ISSUE",  // 带生产计划的领料出库
            "AUTO_BOM",          // 按 BOM 自动扣料
            "MATERIAL_RETURN"    // 生产退料 —— 金额为负, 是上面那些的冲减
    );

    /** 不进凭证：每条写明<b>为什么</b>。⛔ 这是留痕，不是备注。 */
    public static final Map<String, String> EXCLUDED = Map.of(
            "TRANSFER_OUT",
            "仓库之间移库, 货还在厂里 —— 不是消耗。实测 23 行 1969.00 元此前被记成「原料发出」",

            "MANUAL_ISSUE",
            "不带生产计划的手工出库。Owner 2026-08-27 裁定不进凭证 —— 它没有对应的产出可归集",

            "ADJUSTMENT",
            "库存调整(BatchConsumptionServiceImpl), 不是领用。差异应当走盘点那条链路的凭证"
    );

    private VoucherConsumptionSourcePolicy() {
    }

    /**
     * 这一行该不该进凭证。
     *
     * <p>⚠️ {@code null} 来源返回 {@code false}：<b>说不出这行是怎么来的，就不该记进账</b>。
     * （实测全后端有 4 个写入点不设 {@code sourceType}；生产库当前 0 行 NULL，
     * 但那是巧合不是保证。）</p>
     */
    public static boolean isProductionConsumption(String sourceType) {
        return sourceType != null && IN_VOUCHER.contains(sourceType);
    }

    /** 已知但被排除的来源 —— 与「没见过的来源」要分开报，后者是需要有人看一眼的信号。 */
    public static boolean isKnownExclusion(String sourceType) {
        return sourceType != null && EXCLUDED.containsKey(sourceType);
    }
}
