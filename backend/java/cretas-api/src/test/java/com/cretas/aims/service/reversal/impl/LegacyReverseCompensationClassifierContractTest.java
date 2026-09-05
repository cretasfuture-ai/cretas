package com.cretas.aims.service.reversal.impl;

import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 🔴 REVERSE 行的分类必须带<b>符号</b>这一维；负量的「遗留 IN 腿补偿」按<b>撤回日志</b>认领，
 * ⛔ 不按 sourceRef 前缀猜。(任务 BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906)
 *
 * <h2>缺陷长什么样（LIUSHANMEN {@code cretas_prod_db}，2026-09-06 只读实测）</h2>
 *
 * <p>{@code e774ebf298}(#391) 之前的整单撤回给 IN 腿写 REVERSE 时<b>不写 report_id</b>。
 * 那些行于是长成：
 *
 * <pre>
 * id=141 sfi=370 REVERSE/REVERSAL  source_ref='reversal-log:18'  qty=-1.250000  report_id=NULL
 * id=142 sfi=371 REVERSE/REVERSAL  source_ref='reversal-log:18'  qty=-1.000000  report_id=NULL
 * id=143 sfi=372 REVERSE/REVERSAL  source_ref='reversal-log:18'  qty=-1.000000  report_id=NULL
 * </pre>
 *
 * <p>与「外部领用还回」(<b>正量</b>) <b>逐字段同形</b>，唯一区别是符号。
 * {@code isExternalConsumptionReverse} 原来只看前缀 ⇒ 把它们认成「还回」⇒
 * {@code validateFullWipLedgersBeforeMutation} 的 {@code signum() <= 0} 守卫抛
 * {@code PARTIAL_COMPENSATION}「外部领用还回流水数量非法」⇒
 * <b>sfi 370/371/372 此后任何一次整单撤回都 409</b>。
 *
 * <h2>为什么加的是「符号」而不是再放宽一条前缀</h2>
 *
 * <p>前缀是<b>代理判据</b>（形态 E：代理会不断加启发式，直到有人把闸关掉）。符号不是代理 ——
 * 它是这两种语义在账本上<b>本来就有</b>的区分，{@code replayMovingAverage} 的权威口径
 * （{@code produced = ΣIN + Σ(REVERSE<0)}；{@code consumed = Σ|OUT| − Σ(REVERSE>0)}）
 * 就建立在它上面。
 *
 * <h2>⚠️ 这些行 {@code ClerkLedgerPairingContractTest} 判不了</h2>
 *
 * <p>那条闸是 Mockito 台、只覆盖 clerk 族，而且它的不变式把 {@code REVERSE} 整个
 * <b>过滤掉</b>（{@code Rig.ledgerBalance()}）。拿它去量 sfi 372（ΣIN=2.00、
 * 一条 REVERSE −1.00、账面 produced=1.00）会算出 2.00 ≠ 1.00 —— 它会对一条<b>健康</b>的行报红。
 * ⇒ 这里单开一条，⛔ 不去改那条闸的口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("REVERSE 分类器：符号这一维 + 遗留 IN 腿补偿按撤回日志认领")
class LegacyReverseCompensationClassifierContractTest {

    private static final String FACTORY = "LIUSHANMEN";

    @Mock private ReportReversalLogRepository reversalLogRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private FinishedGoodsBatchRepository fgbRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkProcessTaskRepository workProcessTaskRepository;

    @InjectMocks
    private ReportReversalServiceImpl service;

    // ────────────────────────── 装置：生产实测行的 1:1 复刻 ──────────────────────────

    private static SemiFinishedInventoryTransaction txn(
            long id, long sfiId, String type, String sourceType, String ref,
            String qty, Long reportId) {
        return SemiFinishedInventoryTransaction.builder()
                .id(id).factoryId(FACTORY).semiFinishedId(sfiId)
                .txnType(type).sourceType(sourceType).sourceRef(ref)
                .quantity(new BigDecimal(qty)).reportId(reportId)
                .build();
    }

    /** 生产 id=141：负量、无 report_id、{@code reversal-log:18}。 */
    private static SemiFinishedInventoryTransaction legacyInLegRow() {
        return txn(141L, 370L, SemiFinishedInventoryTransaction.TxnType.REVERSE,
                SemiFinishedInventoryTransaction.SourceType.REVERSAL,
                "reversal-log:18", "-1.250000", null);
    }

    /** 真正的「外部领用还回」形状：<b>正量</b>、无 report_id、同一个前缀。 */
    private static SemiFinishedInventoryTransaction externalRestoreRow() {
        return txn(999L, 370L, SemiFinishedInventoryTransaction.TxnType.REVERSE,
                SemiFinishedInventoryTransaction.SourceType.REVERSAL,
                "reversal-log:18", "1.250000", null);
    }

    // ────────────────────────── ① 分类器本身 ──────────────────────────

    @Test
    @DisplayName("负量 + 无 report_id + reversal-log: 前缀 —— ⛔ 不是「外部领用还回」")
    void negativeReverseIsNotAnExternalConsumptionRestore() {
        assertThat(ReportReversalServiceImpl.isExternalConsumptionReverse(legacyInLegRow()))
                .as("符号这一维没了 ⇒ 负量的遗留 IN 腿补偿又会被认成「还回」并把整条 SFI 锁死")
                .isFalse();
    }

    @Test
    @DisplayName("阳性对照：同前缀的<b>正量</b>行仍然是「外部领用还回」（⛔ 别把闸收成恒假式）")
    void positiveReverseIsStillAnExternalConsumptionRestore() {
        assertThat(ReportReversalServiceImpl.isExternalConsumptionReverse(externalRestoreRow()))
                .as("正量那一支被误伤 ⇒ 小结撤销 / #527 还回回料整条路径反过来被锁死")
                .isTrue();
        assertThat(ReportReversalServiceImpl.isExternalConsumptionReverse(
                txn(998L, 370L, SemiFinishedInventoryTransaction.TxnType.REVERSE,
                        SemiFinishedInventoryTransaction.SourceType.REVERSAL,
                        "interim-settle-reverse-out:CLK-SEMI-x", "3.000000", null)))
                .isTrue();
    }

    @Test
    @DisplayName("遗留 IN 腿补偿：撤回日志认领了它才算数，⛔ 不看前缀")
    void legacyCompensationIsProvenByTheReversalLogNotThePrefix() {
        SemiFinishedInventoryTransaction row = legacyInLegRow();
        assertThat(ReportReversalServiceImpl.isLegacyInLegCompensation(row, Set.of(141L)))
                .as("撤回日志的 reverted_txn_ids 认领了这条流水 ⇒ 血缘成立")
                .isTrue();
        // 阴性对照：同一条行, 没有任何日志认领它 ⇒ 判不出来 ⇒ fail-closed
        assertThat(ReportReversalServiceImpl.isLegacyInLegCompensation(row, Set.of(9999L)))
                .as("没有日志认领却仍判 true ⇒ 判据退化成「前缀对就放行」, 等于没有判据")
                .isFalse();
        // 阴性对照：正量行不属于这一类（它归 isExternalConsumptionReverse）
        assertThat(ReportReversalServiceImpl.isLegacyInLegCompensation(
                externalRestoreRow(), Set.of(999L)))
                .as("正量被认成 IN 腿补偿 ⇒ 「还回领用」会被算成「退回产出」, 净产出被冲掉")
                .isFalse();
        // 阴性对照：已经有 report_id 的行走正常配对路径，不该落进这条兜底
        assertThat(ReportReversalServiceImpl.isLegacyInLegCompensation(
                txn(145L, 370L, SemiFinishedInventoryTransaction.TxnType.REVERSE,
                        SemiFinishedInventoryTransaction.SourceType.REVERSAL,
                        "reversal-log:18", "-1.250000", 23920L), Set.of(145L)))
                .isFalse();
    }

    // ────────────────────────── ② 闸：370 形状的完整流水必须放行 ──────────────────────────

    /**
     * sfi 370 的完整流水（生产 1:1）：
     * <pre>
     * 136 IN      +1.25  report 23919 (已软删)
     * 137 OUT     -1.25  report 23920 (已软删)
     * 141 REVERSE -1.25  report NULL   <- 遗留 IN 腿补偿
     * 144 IN      +1.25  report 23925 (活)
     * 145 REVERSE +1.25  report 23920
     * 147 OUT     -1.25  report 23926 (活)
     * </pre>
     * 对账：produced = 1.25+1.25-1.25 = 1.25 ✓；consumed = 1.25+1.25-1.25 = 1.25 ✓。
     */
    private static List<SemiFinishedInventoryTransaction> ledger370() {
        return List.of(
                txn(136L, 370L, SemiFinishedInventoryTransaction.TxnType.IN,
                        SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT,
                        "c57c36e0-B10888-S1-1932", "1.250000", 23919L),
                txn(137L, 370L, SemiFinishedInventoryTransaction.TxnType.OUT,
                        SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME,
                        "TASK-1933", "-1.250000", 23920L),
                legacyInLegRow(),
                txn(144L, 370L, SemiFinishedInventoryTransaction.TxnType.IN,
                        SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT,
                        "c57c36e0-B10888-S1-1932", "1.250000", 23925L),
                txn(145L, 370L, SemiFinishedInventoryTransaction.TxnType.REVERSE,
                        SemiFinishedInventoryTransaction.SourceType.REVERSAL,
                        "reversal-log:18", "1.250000", 23920L),
                txn(147L, 370L, SemiFinishedInventoryTransaction.TxnType.OUT,
                        SemiFinishedInventoryTransaction.SourceType.SECONDARY_CONSUME,
                        "TASK-1933", "-1.250000", 23926L));
    }

    private void stubLedger(List<Long> claimedTxnIds) {
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(anyLong())).thenReturn(ledger370());
        ReportReversalLog log = ReportReversalLog.builder()
                .id(18L).factoryId(FACTORY).batchId(10888L)
                .revertedTxnIds(claimedTxnIds)
                .build();
        when(reversalLogRepo.findByFactoryIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(log));
    }

    /** {@code validateFullWipLedgersBeforeMutation} 是 private —— 反射直调，⛔ 不复刻它的逻辑。 */
    private void runGate() throws Exception {
        Method m = ReportReversalServiceImpl.class.getDeclaredMethod(
                "validateFullWipLedgersBeforeMutation", String.class, Set.class, Map.class);
        m.setAccessible(true);
        try {
            // 新一轮撤回覆盖的是【活着的】那两条报工 23925 / 23926
            m.invoke(service, FACTORY, Set.of(370L), Map.of(
                    23925L, List.of(ledger370().get(3)),
                    23926L, List.of(ledger370().get(5))));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("🔴 370 形状（含遗留 IN 腿补偿）必须放行 —— 这就是生产上被锁死的那条行")
    void healthyLedgerWithLegacyCompensationPassesTheGate() {
        stubLedger(List.of(141L, 142L, 143L, 145L, 146L));
        assertThatCode(this::runGate)
                .as("撤回日志认领了 141, 账也对得上 —— 仍然 409 ⇒ 缺陷没修好")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("阴性对照：同一条行没有日志认领 ⇒ 仍然 fail-closed 拦住（⛔ 别修成一律放行）")
    void unclaimedOrphanReverseIsStillRejected() {
        stubLedger(List.of(145L, 146L));   // 141 不在认领清单里
        assertThatThrownBy(this::runGate)
                .isInstanceOf(BusinessException.class)
                .as("无人认领的孤儿 REVERSE 被放行 ⇒ 这道闸不再守任何东西")
                .hasMessageContaining("孤立");
    }
}
