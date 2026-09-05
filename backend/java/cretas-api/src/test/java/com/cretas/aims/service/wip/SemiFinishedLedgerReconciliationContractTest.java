package com.cretas.aims.service.wip;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
 * ⚠️ 本类是那份口径的<b>第二份表达</b>（形态 D）——所以它只被允许做一件事：
 * <b>拿存量行去校验它</b>，⛔ 不作为任何生产路径的输入。
 * 生产侧的三态探针 {@code scripts/ops/wip_ledger_reconciliation_probe.sh} 用的是<b>逐字相同</b>的公式。
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

    // ────────────────────────── ③ 阴性对照：真坏的行必须报红 ──────────────────────────

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
