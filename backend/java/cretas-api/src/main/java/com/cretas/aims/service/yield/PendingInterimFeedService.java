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
     * @param unsettlablePlans   本次遍历里<b>因为计划永远结不掉而被排除</b>的行 (及那些计划的 id)。
     *                           ⛔ 不要把它并进 {@code unresolvedBatchNos} —— 那一栏说的是
     *                           「这个批次的待扣量算不出来」(未知), 这一栏说的是
     *                           「这些行的待扣量确定是 0」(已排除)。两件相反的事。
     */
    record Snapshot(Map<String, BigDecimal> pendingByBatchNo,
                    Set<String> unresolvedBatchNos,
                    UnsettlablePlans unsettlablePlans) {

        private static final Snapshot EMPTY = new Snapshot(Map.of(), Set.of());

        /** 旧的两参构造 —— 既有调用方 (测试桩等) 不必知道排除栏。 */
        public Snapshot(Map<String, BigDecimal> pendingByBatchNo, Set<String> unresolvedBatchNos) {
            this(pendingByBatchNo, unresolvedBatchNos, UnsettlablePlans.none());
        }

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

    /**
     * 🔴🔒 幽灵预扣的<b>留痕栏</b> (BUG-GHOST-PENDING-FEED-DELETED-PLANS, 2026-09-06)。
     *
     * <p><b>为什么这一栏必须存在</b>: 修法本身是「把结不掉的行从待扣里剔出去」, 而
     * <b>静默剔除与从来没有过这些行长得一模一样</b>。真出问题时 (比如误删计划把一整批在产的行剔掉了),
     * 唯一能看出来的读数就是这里的计数。⛔ 只加 JOIN 不加计数 = 把一个可观测的缺陷换成一个不可观测的。
     *
     * @param orphanRowCount    因<b>计划够不着</b>(硬删无行 / 软删 deleted_at IS NOT NULL) 被排除的行数。
     *                          两种成因不区分: 经 {@code @Where} 的仓库查询里它们本就是同一个读数,
     *                          而且后果相同 —— 小结与结单都加载不到该计划, 这些行永远盖不上戳。
     * @param orphanPlanIds     上述行指向的计划 id (排重), 供人工核对是不是误删。
     * @param cancelledRowCount 因计划 {@code CANCELLED} 被排除的行数。取消是<b>终态</b>:
     *                          小结显式拒绝 CANCELLED, 结单只收 IN_PROGRESS/PENDING ⇒ 同样永远盖不上戳;
     *                          且取消审批路径本身已冲销并恢复过库存, 再算一次待扣就是重复预扣。
     * @param cancelledPlanIds  上述计划 id (排重)。
     * @param anomalousTerminalPlanIds
     *                          {@code COMPLETED / PENDING_APPROVAL} 计划上<b>仍然</b>有未结行 ——
     *                          🔴 <b>这些行没有被排除</b>, 照常计入待扣。此栏只是把异常喊出来:
     *                          正常结单会同事务给本计划全部未结行盖戳, 所以出现在这里就意味着
     *                          「结单没盖到」或「被撤回冲销后重新变回待结」。后者待扣是<b>真的</b>
     *                          (冲销已把货退回库存, 等着重新结算), 所以⛔ 不能顺手剔掉。
     */
    record UnsettlablePlans(int orphanRowCount,
                            Set<String> orphanPlanIds,
                            int cancelledRowCount,
                            Set<String> cancelledPlanIds,
                            Set<String> anomalousTerminalPlanIds) {

        private static final UnsettlablePlans NONE =
                new UnsettlablePlans(0, Set.of(), 0, Set.of(), Set.of());

        /** 一行都没排除。 */
        public static UnsettlablePlans none() {
            return NONE;
        }

        /** 本次遍历一共剔掉了几行 (两类之和)。 */
        public int totalExcludedRows() {
            return orphanRowCount + cancelledRowCount;
        }

        /** 有没有任何值得记一笔的事 —— 决定要不要打那行日志。 */
        public boolean isSilent() {
            return totalExcludedRows() == 0 && anomalousTerminalPlanIds.isEmpty();
        }
    }
}
