package com.cretas.aims.service.yield;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 🔴🔒🔒 延迟扣减盲区 — 待小结 SFI 投料聚合 (sibling of #1216/#1219, 2026-07-05)。
 *
 * <p>逐工序报工写「待小结」产出/投料 ({@code process_sheet_rows.interim_settled_at IS NULL}),
 * 但常驻半成品库存(SFI) 投料只在「小结」({@code InterimSettleServiceImpl} §②A) 才严格扣减
 * ({@code consumeClerkSemiStrict})。在「报工 → 小结」窗口内, SFI 的 {@code availableQuantity} 是
 * <b>stale-high</b> (账面未扣, 货已投产)。
 *
 * <p>这与原料 {@code MaterialConsumption} 的延迟扣减 (#1216/#1219 已修) 同构, 但 SFI 投料
 * <b>不写 MaterialConsumption</b> — 只存在于 {@code row_payload.upstreamSources} JSON。故须解析
 * 待小结行的 payload, 聚合每个来源批次的待扣投料量, 供半成品盘点
 * ({@code SemiFinishedStocktakeServiceImpl}) 把快照账面校正为「货架实物」
 * ({@code availableQuantity − 待扣 SFI 投料}) —— 否则仓管诚实盘点触发假盘亏 + 假 6602/1405 凭证 +
 * 后续小结二次扣减 (库存虚低 + 财务双记), 这正是 #1216 为原料盘点修复的同一盲区。
 *
 * <p><b>只覆盖 strict 投料路径</b> (semiFinished 引用常驻 SFI, 扣减确定性): 折算与 strict 扣减同源
 * ({@code resolveSemiFeedQtyInSourceUnit}), 折算失败 (盒装缺每盒克重 / 批次缺失) → 诚实 null →
 * <b>跳过不减</b> (此时小结本身也会 loud-fail 无法扣减, 故不减 = 货实际不会离库, 口径一致)。
 * 计划自身前序小结半成品的 anchor 容忍扣减路径 (consumeClerkSemi, clamp) <b>不</b>纳入 —— 其扣减量
 * 不可精确预判, 强减会造成假盘盈腐蚀财务 (honest-null, 同 #1219「族未知不减」)。
 */
public interface PendingInterimFeedService {

    /**
     * 待小结 <b>SFI 投料</b> 聚合: {@code intermediateBatchNo → Σ 待扣量(来源原生单位)}。
     * 无待扣的批次不在 map 中 (调用方按 0 处理 → 账面不变)。整厂一次性预取。
     *
     * <p>= {@link #pendingSemiFeedSnapshot(String)} 的 {@code pendingByBatchNo}。⛔ 不要另写一遍遍历 ——
     * 同一个口径有两份实现, 它一定会漂 (本文件下面那条 {@code stockConsumedAtSubmit} 就是这么漂出来的)。
     */
    Map<String, BigDecimal> pendingSemiFeedByBatchNo(String factoryId);

    /**
     * 同一次遍历给出两件事: <b>算得出来的待扣量</b> 与 <b>算不出来的批次</b>。
     *
     * <p>🔴 为什么第二件事必须单独端出来: 在 {@link #pendingSemiFeedByBatchNo} 里,
     * 「这个批次没有待扣」和「这个批次的待扣量我算不出来」<b>长得一模一样</b> —— 都是 key 不在 map 里。
     * 盘点侧据此不减是对的 (折算不出来时小结本身也 loud-fail, 货不会离库, 账面=实物);
     * 但<b>投料下拉</b>拿同一个口径, 就会把一个「待扣量未知」的批次画成「余量干净可以领」,
     * 那是把「我不知道」翻译成「是 0」。⇒ 下拉侧读 {@link Snapshot#unresolvedBatchNos()} 把这一行标出来。
     */
    Snapshot pendingSemiFeedSnapshot(String factoryId);

    /**
     * 待扣读数 + 读数的<b>来源标记</b>。
     *
     * @param pendingByBatchNo   已折算到来源 SFI 原生单位的待扣量 (恒 &gt; 0); 无待扣的批次不在其中
     * @param unresolvedBatchNos 折算返回诚实 null 的来源批次 (计数单位缺每盒克重 / 批次已不存在) ——
     *                           这些批次的待扣量是<b>未知</b>, 不是 0
     */
    record Snapshot(Map<String, BigDecimal> pendingByBatchNo, Set<String> unresolvedBatchNos) {

        private static final Snapshot EMPTY = new Snapshot(Map.of(), Set.of());

        /** 服务未注入 (单测 / 未接线) 时的空读数 —— 与「整厂没有待扣」同义 (旧口径, 向后兼容)。 */
        public static Snapshot empty() {
            return EMPTY;
        }

        /** 该批次的待扣量; 无待扣 → {@code ZERO}。⚠️ ZERO 只说「没有待扣」, 不说「算不出来」—— 后者问 {@link #isUnresolved}。 */
        public BigDecimal pendingOf(String intermediateBatchNo) {
            BigDecimal v = intermediateBatchNo == null ? null : pendingByBatchNo.get(intermediateBatchNo);
            return v == null ? BigDecimal.ZERO : v;
        }

        /** 该批次至少有一条投料引用折算不出来 ⇒ 它的待扣量<b>不完整</b>, 可用量不可当作干净读数。 */
        public boolean isUnresolved(String intermediateBatchNo) {
            return intermediateBatchNo != null && unresolvedBatchNos.contains(intermediateBatchNo);
        }
    }
}
