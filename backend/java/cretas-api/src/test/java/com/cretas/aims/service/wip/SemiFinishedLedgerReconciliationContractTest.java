package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.ReportReversalLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.reversal.impl.ReportReversalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 半成品流水账<b>对账闸</b>：库存行上的三个数必须由<b>存进去的流水</b>推得出来。
 * (任务 BUG-SFI-REVERSE-CLASSIFIER-LOCKOUT-20260906)
 *
 * <h2>权威口径（⛔ 不是本类发明的，抄自它的两个出处）</h2>
 *
 * <pre>
 * produced  = ΣIN + Σ(REVERSE where qty &lt; 0)
 * consumed  = Σ|OUT| − Σ(REVERSE where qty &gt; 0)
 * available = produced − consumed + adjustment
 * </pre>
 *
 * <p>出处：{@code WipLedgerEntries.settlementRestoreRow} 的符号表
 * （负量 REVERSE = 退回产出、正量 REVERSE = 还回领用）与
 * {@code ReportReversalServiceImpl.replayMovingAverage} 的实现。
 *
 * <h2>🔴 这份口径全仓有<b>三份</b>，所以有一条对拍（形态 D）</h2>
 *
 * <table border="1">
 *   <caption>三份表达与它们各自怎么被钉住</caption>
 *   <tr><th>#</th><th>在哪</th><th>语言</th><th>怎么钉</th></tr>
 *   <tr><td>1</td><td>{@code ReportReversalServiceImpl.replayMovingAverage}</td><td>Java</td>
 *       <td><b>权威</b>（回写库存行的就是它）</td></tr>
 *   <tr><td>2</td><td>本类的 {@link #replay}</td><td>Java</td>
 *       <td>{@link #crossCheckAgainstReplayMovingAverage()} <b>对拍</b>：
 *           同一批 fixture 喂两边，三个数必须逐个相等</td></tr>
 *   <tr><td>3</td><td>{@code scripts/ops/wip-ledger-reconciliation-probe.sh} 的 SQL</td><td>SQL</td>
 *       <td>跨语言抽不成一份 ⇒ 脚本头部写明<b>怎么与 Java 对拍</b>（拿 prod 某行的三个数
 *           与 {@code replayMovingAverage} 的结果并排打印）</td></tr>
 * </table>
 *
 * <p>⚠️ 之前这三份只靠一句「逐字同口径」的注释钉着 —— <b>注释拦不住漂移</b>
 * （本仓 {@code fill_offers.py} 的先例：白纸黑字写过的告诫，隔壁文件照样又踩一次）。
 * 对拍是可执行的那一份。
 *
 * <p>⚠️ 本类的 {@link #replay} 只被允许做一件事：<b>拿存量行去校验账面值</b>，
 * ⛔ 不作为任何生产路径的输入。
 *
 * <h2>🔴 为什么这条闸不能交给 {@code ClerkLedgerPairingContractTest}</h2>
 *
 * <p>那条闸的不变式（{@code Rig.ledgerBalance()}）把 {@code REVERSE} 行整个
 * <b>过滤掉</b>，且只跑 clerk 族的 Mockito 台。拿它去量下面
 * {@code healthyCycleWithReverse}（生产 sfi 370/371 的形状：ΣIN=2.50、
 * 一条 REVERSE −1.25、一条 REVERSE +1.25、Σ|OUT|=2.50，账面 produced=1.25）
 * 会算出 {@code ΣIN+ΣOUT = 0.00}，与账面 1.25 不符 —— <b>它会对一条健康的行报红</b>。
 * ⇒ 这里单开一条，⛔ 不去改那条闸的口径。
 *
 * <h2>为什么用 {@code @DataJpaTest} 而不是 Mockito</h2>
 *
 * <p>要守的是「<b>存进去</b>的行推得出账面值」。Mockito 台里流水是我手写的对象，
 * 「存」这一步不存在，于是精度 / 列宽 / 空值这些只有真存过才暴露的东西一概看不见。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("半成品流水账对账闸：账面三个数必须由存量流水推得出来")
class SemiFinishedLedgerReconciliationContractTest {

    private static final String FACTORY = "F-RECON-GATE";

    @Autowired private SemiFinishedInventoryRepository wipRepo;
    @Autowired private SemiFinishedInventoryTransactionRepository txnRepo;

    // ────────────────────────── 权威不变式（唯一一处表达） ──────────────────────────

    private record Reading(BigDecimal produced, BigDecimal consumed, BigDecimal available) {}

    /** 由<b>存量流水</b>推出账面三个数。⚠️ 与 replayMovingAverage / 探针脚本逐字同口径。 */
    private static Reading replay(List<SemiFinishedInventoryTransaction> ledger, BigDecimal adjustment) {
        BigDecimal produced = BigDecimal.ZERO;
        BigDecimal consumed = BigDecimal.ZERO;
        for (SemiFinishedInventoryTransaction t : ledger) {
            BigDecimal q = t.getQuantity();
            if (q == null) {
                continue;
            }
            if (SemiFinishedInventoryTransaction.TxnType.IN.equals(t.getTxnType())) {
                produced = produced.add(q);
            } else if (SemiFinishedInventoryTransaction.TxnType.OUT.equals(t.getTxnType())) {
                consumed = consumed.add(q.abs());
            } else if (SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType())) {
                if (q.signum() < 0) {
                    produced = produced.add(q);          // 负量 = 退回产出
                } else {
                    consumed = consumed.subtract(q);     // 正量 = 还回领用
                }
            }
        }
        return new Reading(produced, consumed,
                produced.subtract(consumed).add(adjustment == null ? BigDecimal.ZERO : adjustment));
    }

    private List<String> disagreements(Long sfiId) {
        SemiFinishedInventory row = wipRepo.findById(sfiId).orElseThrow();
        Reading r = replay(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfiId),
                row.getAdjustmentQuantity());
        List<String> out = new ArrayList<>();
        if (row.getProducedQuantity().compareTo(r.produced()) != 0) {
            out.add("produced 账面=" + row.getProducedQuantity() + " 流水=" + r.produced());
        }
        if (row.getConsumedQuantity().compareTo(r.consumed()) != 0) {
            out.add("consumed 账面=" + row.getConsumedQuantity() + " 流水=" + r.consumed());
        }
        if (row.getAvailableQuantity().compareTo(r.available()) != 0) {
            out.add("available 账面=" + row.getAvailableQuantity() + " 流水=" + r.available());
        }
        return out;
    }

    // ────────────────────────── 装置 ──────────────────────────

    private Long seedRow(String batchNo, String produced, String consumed, String available) {
        SemiFinishedInventory row = wipRepo.saveAndFlush(SemiFinishedInventory.builder()
                .factoryId(FACTORY).intermediateBatchNo(batchNo)
                .productTypeId("PT-RECON")
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .availableQuantity(new BigDecimal(available))
                .adjustmentQuantity(BigDecimal.ZERO)
                .unit("kg")
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build());
        return row.getId();
    }

    private void seedTxn(Long sfiId, String type, String sourceType, String ref,
                         String qty, Long reportId) {
        txnRepo.saveAndFlush(SemiFinishedInventoryTransaction.builder()
                .factoryId(FACTORY).semiFinishedId(sfiId)
                .txnType(type).sourceType(sourceType).sourceRef(ref)
                .quantity(new BigDecimal(qty)).reportId(reportId)
                .build());
    }

    // ────────────────────────── ① 健康：370/371 形状的完整周期 ──────────────────────────

    @Test
    @DisplayName("🔴 健康：产出→领用→撤回(负 REVERSE)→重做→还回(正 REVERSE)→再领 —— 必须对得上")
    void healthyCycleWithReverseReconciles() {
        Long sfi = seedRow("RECON-CYCLE-370", "1.25", "1.25", "0.00");
        seedTxn(sfi, "IN", "PRODUCTION_OUTPUT", "recon-S1", "1.250000", 23919L);
        seedTxn(sfi, "OUT", "SECONDARY_CONSUME", "TASK-1933", "-1.250000", 23920L);
        seedTxn(sfi, "REVERSE", "REVERSAL", "reversal-log:18", "-1.250000", null);
        seedTxn(sfi, "IN", "PRODUCTION_OUTPUT", "recon-S1", "1.250000", 23925L);
        seedTxn(sfi, "REVERSE", "REVERSAL", "reversal-log:18", "1.250000", 23920L);
        seedTxn(sfi, "OUT", "SECONDARY_CONSUME", "TASK-1933", "-1.250000", 23926L);

        assertThat(disagreements(sfi))
                .as("这条行在生产上是健康的(LIUSHANMEN sfi 370) —— 闸对它报红就是误报, "
                        + "而误报正是让人把闸关掉的那个东西(形态 E)")
                .isEmpty();
    }

    // ────────────────────────── ② 健康：F006 342 形状的重复领用 ──────────────────────────

    @Test
    @DisplayName("🔴 健康：同一个 source_ref 出现 4 次(同一工序反复领用) —— 合法, 不许当重复过账")
    void repeatedConsumptionOnTheSameSourceRefReconciles() {
        Long sfi = seedRow("RECON-REPEAT-342", "240.00", "240.00", "0.00");
        seedTxn(sfi, "IN", "PRODUCTION_OUTPUT", "recon-S1-1788", "240.000000", 23830L);
        for (long i = 0; i < 4; i++) {
            // ⚠️ source_ref 相同、report_id 不同 —— 生产 F006 sfi 342 的真实形状。
            //    在 (sfi_id, source_ref) 上加唯一键会把这四行拒掉, 那是**错的**修法。
            seedTxn(sfi, "OUT", "SECONDARY_CONSUME", "TASK-1789", "-60.000000", 23831L + i * 2);
        }
        assertThat(disagreements(sfi))
                .as("重复领用被判成对不上 ⇒ 会把「唯一键」当成修法, 而那会拒掉真实过账")
                .isEmpty();
    }

    // ────────────────────────── ③ 对拍：本类的推导 vs Java 权威实现 ──────────────────────────

    /**
     * 🔴 <b>对拍</b>：同一批存量行，喂给 {@code replayMovingAverage}（权威）和本类的
     * {@link #replay}（第二份表达），三个数必须逐个相等。
     *
     * <p>这是把「逐字同口径」那句注释<b>变成可执行的</b>那一步。注释会漂，对拍不会。
     *
     * <p>⚠️ 用 {@code ReflectionTestUtils.invokeMethod} 调那个 private 方法 ——
     * 与既有的 {@code ReplayMovingAverageTest} 同一个做法，⛔ 不为了测试放宽生产可见性。
     * 权威实现里除 {@code wipRepo} / {@code txnRepo} 外的协作者它一概不碰，所以其余构造参数给 mock。
     */
    @Test
    @DisplayName("🔴 对拍：本类的推导必须与 replayMovingAverage 逐个数相等(形态 D 的可执行钉子)")
    void crossCheckAgainstReplayMovingAverage() {
        // 🔴 账面值**刻意写错**(2.50/0.00/2.50), 而正确答案是 1.25/1.25/0.00 —— 三个数全不同。
        //    如果 replayMovingAverage 根本没被调到(反射打空 / rig 坏了), 回读到的就是这三个
        //    错的数, 断言当场红 —— ⛔ 不允许「两侧相等」是因为两侧都没动过(形态 B′ 恒真式)。
        // ⚠️ 这个错值必须 ≤ ΣIN(=2.50): 权威实现开头那道 WIP_LEDGER_INCOMPLETE 闸比的正是
        //    「账面 produced vs ΣIN」, 写成 9.99 会让它先抛异常, 对拍就跑不到了
        //    (实测过: 「账面产出 9.99, 而流水账里只记过 2.5 的入库」)。
        Long sfi = seedRow("RECON-XCHECK-370", "2.50", "0.00", "2.50");
        seedTxn(sfi, "IN", "PRODUCTION_OUTPUT", "xchk-S1", "1.250000", 23919L);
        seedTxn(sfi, "OUT", "SECONDARY_CONSUME", "TASK-1933", "-1.250000", 23920L);
        seedTxn(sfi, "REVERSE", "REVERSAL", "reversal-log:18", "-1.250000", null);
        seedTxn(sfi, "IN", "PRODUCTION_OUTPUT", "xchk-S1", "1.250000", 23925L);
        seedTxn(sfi, "REVERSE", "REVERSAL", "reversal-log:18", "1.250000", 23920L);
        seedTxn(sfi, "OUT", "SECONDARY_CONSUME", "TASK-1933", "-1.250000", 23926L);

        // ① 本类的推导（第二份表达）
        Reading mine = replay(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(sfi), BigDecimal.ZERO);

        // ② 权威实现，跑在**同一批存量行**上
        ReportReversalServiceImpl authority = new ReportReversalServiceImpl(
                mock(ReportReversalLogRepository.class), mock(ProductionBatchRepository.class),
                mock(ProductionReportRepository.class), wipRepo, txnRepo,
                mock(FinishedGoodsBatchRepository.class), mock(UserRepository.class),
                mock(WorkProcessTaskRepository.class), mock(NotificationService.class));
        ReflectionTestUtils.invokeMethod(authority, "replayMovingAverage",
                sfi, FACTORY, LocalDateTime.now());
        SemiFinishedInventory afterReplay = wipRepo.findById(sfi).orElseThrow();

        // 🔴 先把两侧都打出来 —— 相等这个读数本身要看得见, ⛔ 不只在红的时候才知道两侧是什么
        System.out.println("XCHECK authority(replayMovingAverage) produced=" + afterReplay.getProducedQuantity()
                + " consumed=" + afterReplay.getConsumedQuantity()
                + " available=" + afterReplay.getAvailableQuantity());
        System.out.println("XCHECK derivation(this test)          produced=" + mine.produced()
                + " consumed=" + mine.consumed() + " available=" + mine.available());

        assertThat(afterReplay.getProducedQuantity())
                .as("produced 两份口径不一致 ⇒ 对账闸/探针在用一份【已经漂了】的公式")
                .usingComparator(BigDecimal::compareTo).isEqualTo(mine.produced());
        assertThat(afterReplay.getConsumedQuantity())
                .as("consumed 两份口径不一致 —— 正/负量 REVERSE 的分流是最容易漂的那一处")
                .usingComparator(BigDecimal::compareTo).isEqualTo(mine.consumed());
        assertThat(afterReplay.getAvailableQuantity())
                .as("available 两份口径不一致 ⇒ 可领余量会被算错(幻影库存 / 凭空少账)")
                .usingComparator(BigDecimal::compareTo).isEqualTo(mine.available());
    }

    // ────────────────────────── ④ 阴性对照：真坏的行必须报红 ──────────────────────────

    @Test
    @DisplayName("阴性对照：账面 produced 300 而流水只有一条 REVERSE −225 —— 必须报红")
    void genuinelyBrokenRowIsReported() {
        // F006 sfi 343 的形状: 三条带外手工 REVERSE, 一条 IN 都没有。
        Long sfi = seedRow("RECON-BROKEN-343", "300.00", "0.00", "75.00");
        seedTxn(sfi, "REVERSE", "REVERSAL", "manual-cleanup: 判据B 走查冲销", "-225.000000", null);

        List<String> found = disagreements(sfi);
        assertThat(found)
                .as("这条行的账面 produced 解释不了 —— 闸对它保持绿 ⇒ 闸在守空气")
                .isNotEmpty();
        assertThat(String.join(" | ", found))
                .as("报的必须是 produced 那一项(账面 300 vs 流水 -225), ⛔ 不是随便哪一项红了都算")
                .contains("produced");
    }
}
