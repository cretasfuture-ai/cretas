package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G02: 半成品余额的校验 / 扣减 / 撤回 / 流水 必须用同一个公式 {@code produced − consumed + adjustment}。
 *
 * <p>反例 (修复前实测形状): 产出 100、已耗 20、盘亏 −30 ⇒ 真余额 50。旧校验用 produced − consumed = 80,
 * 领 60 被放行, 写回 100 − 80 − 30 = −10。盘盈 +30 时真余额 130, 旧校验按 100 拒掉领 100。
 *
 * <p>盘点 ({@code SemiFinishedStocktakeServiceImpl#apply}) 只写 adjustmentQuantity、不动 produced/consumed,
 * 所以这里的夹具直接设 adjustment, 与生产上盘点后的行同形 (available 存量列 = 公式值)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("G02 半成品余额公式: 校验 / 扣减 / 撤回 / 流水 同一口径")
class WipBalanceAdjustmentFormulaTest {

    private static final String FACTORY = "F006";
    private static final String ANCHOR = "CLK-SEMI-PLAN-G02-PT1";

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductionWorkflowInstanceRepository workflowInstanceRepo;
    @Mock private WorkflowTaskPortRepository workflowTaskPortRepo;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WipInventoryServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new WipInventoryServiceImpl(wipRepo, txnRepo, reportRepo, lineageEdgeRepo,
                taskRepo, workProcessRepo, productTypeRepo, workflowInstanceRepo, workflowTaskPortRepo,
                productFamilyResolver, eventPublisher);
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.findByFactoryIdAndReportId(anyString(), any())).thenReturn(List.of());
    }

    /** 盘点后的行: available 存量列 = 公式值 (与 SemiFinishedStocktakeServiceImpl 写出的形状一致)。 */
    private SemiFinishedInventory row(String unit, String produced, String consumed, String adjustment) {
        SemiFinishedInventory r = SemiFinishedInventory.builder()
                .id(77L)
                .factoryId(FACTORY)
                .intermediateBatchNo(ANCHOR)
                .productTypeId("PT1")
                .unit(unit)
                .producedQuantity(new BigDecimal(produced))
                .consumedQuantity(new BigDecimal(consumed))
                .adjustmentQuantity(new BigDecimal(adjustment))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .build();
        r.setAvailableQuantity(new BigDecimal(produced).subtract(new BigDecimal(consumed))
                .add(new BigDecimal(adjustment)));
        return r;
    }

    private void locked(SemiFinishedInventory r) {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, ANCHOR))
                .thenReturn(Optional.of(r));
        when(wipRepo.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
    }

    private SemiFinishedInventoryTransaction onlyLedgerRow() {
        ArgumentCaptor<SemiFinishedInventoryTransaction> txn =
                ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(txnRepo).save(txn.capture());
        return txn.getValue();
    }

    // ── 严格扣减 (SFI 投料, 小结) ──────────────────────────────────────────────

    @Test
    @DisplayName("🔴 盘亏 −30: 余额 50, 领 60 必须拒绝, 库存行与流水一行不动")
    void stocktakeLossBlocksOverdraw() {
        SemiFinishedInventory r = row("kg", "100", "20", "-30");
        locked(r);

        assertThatThrownBy(() -> svc.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("60")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("半成品库存不足")
                .hasMessageContaining("余50");

        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("20");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("50");
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("阳性对照: 盘亏 −30 时领 50 (恰好等于余额) 放行, 余额到 0 且 DEPLETED, 流水 −50 / balanceAfter 0")
    void stocktakeLossAllowsExactBalance() {
        SemiFinishedInventory r = row("kg", "100", "20", "-30");
        locked(r);

        BigDecimal deducted = svc.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("50"));

        assertThat(deducted).isEqualByComparingTo("50");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(r.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
        SemiFinishedInventoryTransaction out = onlyLedgerRow();
        assertThat(out.getQuantity()).isEqualByComparingTo("-50");
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("🔴 盘盈 +30: 产 100、耗 20 ⇒ 余额 110, 领 100 必须允许 (旧校验按 80 拒); 余额 10, 流水 −100 / balanceAfter 10")
    void stocktakeGainAllowsBeyondProduced() {
        SemiFinishedInventory r = row("kg", "100", "20", "30");
        locked(r);

        BigDecimal deducted = svc.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("100"));

        assertThat(deducted).isEqualByComparingTo("100");
        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("120");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(r.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
        SemiFinishedInventoryTransaction out = onlyLedgerRow();
        assertThat(out.getQuantity()).isEqualByComparingTo("-100");
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("撤回: 盘盈 +30 时领 100 再撤回 100 ⇒ 余额回到 110, REVERSE 流水 balanceAfter 110")
    void restoreAfterGainReturnsToFormulaBalance() {
        SemiFinishedInventory r = row("kg", "100", "120", "30");   // 已领 120, 余额 10
        r.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        locked(r);

        svc.restoreClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("100"), "undo-g02", 9L);

        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("20");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("110");
        SemiFinishedInventoryTransaction rev = onlyLedgerRow();
        assertThat(rev.getTxnType()).isEqualTo(SemiFinishedInventoryTransaction.TxnType.REVERSE);
        assertThat(rev.getQuantity()).isEqualByComparingTo("100");
        assertThat(rev.getBalanceAfter()).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("撤回 (宽松路径 restoreClerkSemi): 盘亏 −30, 已领 50 余额 0 ⇒ 还 20 后余额 20 并恢复 AVAILABLE")
    void lenientRestoreUsesSameFormula() {
        SemiFinishedInventory r = row("kg", "100", "70", "-30");   // 余额 0
        r.setStatus(SemiFinishedInventory.Status.DEPLETED);
        locked(r);

        svc.restoreClerkSemi(FACTORY, ANCHOR, new BigDecimal("20"), 9L);

        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("50");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("20");
        assertThat(r.getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
        assertThat(onlyLedgerRow().getBalanceAfter()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("计数⇄重量换算: 盒装 500g/盒, 产 100 盒、耗 20 盒、盘亏 −30 盒 ⇒ 余 50 盒; 投 30kg(=60 盒) 拒, 投 20kg(=40 盒) 放")
    void countUnitConversionUsesSameBalance() {
        ProductType pt = new ProductType();
        pt.setId("PT1");
        pt.setGramsPerUnit(new BigDecimal("500"));
        when(productTypeRepo.findById("PT1")).thenReturn(Optional.of(pt));
        SemiFinishedInventory r = row("盒", "100", "20", "-30");
        locked(r);

        assertThatThrownBy(() -> svc.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("30")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("余50盒")
                .hasMessageContaining("需60盒");
        verify(txnRepo, never()).save(any());

        BigDecimal deducted = svc.consumeClerkSemiStrict(FACTORY, ANCHOR, new BigDecimal("20"));
        assertThat(deducted).as("扣减按来源原生单位 (盒)").isEqualByComparingTo("40");
        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("60");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("10");
        SemiFinishedInventoryTransaction out = onlyLedgerRow();
        assertThat(out.getQuantity()).as("流水与库存行同单位 (盒)").isEqualByComparingTo("-40");
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("10");
    }

    // ── 宽松扣减 (clamp 路径) ────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 宽松扣减: 盘亏 −30 余额 50, 请求 60 ⇒ 只扣 50, 余额 0 (不为负), 流水记实际 −50")
    void lenientConsumeClampsToFormulaBalance() {
        SemiFinishedInventory r = row("kg", "100", "20", "-30");
        locked(r);

        svc.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("60"));

        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("70");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(onlyLedgerRow().getQuantity()).isEqualByComparingTo("-50");
    }

    @Test
    @DisplayName("宽松扣减: 盘盈 +30 余额 130, 请求 120 ⇒ 全额扣 120 (旧版按 produced 夹成 100)")
    void lenientConsumeDoesNotUnderDeductOnGain() {
        SemiFinishedInventory r = row("kg", "100", "0", "30");
        locked(r);

        svc.consumeClerkSemi(FACTORY, ANCHOR, new BigDecimal("120"));

        assertThat(r.getConsumedQuantity()).isEqualByComparingTo("120");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(onlyLedgerRow().getQuantity()).isEqualByComparingTo("-120");
    }

    // ── 二次加工 / 报工领用 (validateSourceWip) / 驳回 ────────────────────────

    @Test
    @DisplayName("二次加工扣减: 存量列被写坏 (80) 也不信它, 按公式余额 50 拒绝领 60")
    void secondaryDeductChecksFormulaNotStoredColumn() {
        SemiFinishedInventory r = row("kg", "100", "20", "-30");
        r.setAvailableQuantity(new BigDecimal("80"));   // 旧漂移: 存量列没纳入盘亏
        locked(r);

        assertThatThrownBy(() -> svc.deductForSecondaryPlan(77L, new BigDecimal("60"), FACTORY, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WIP 余量不足");
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("报工领用前置校验: 存量列 80 但公式余额 50 ⇒ 领 60 拒绝 (WIP_INSUFFICIENT)")
    void validateSourceWipChecksFormula() {
        SemiFinishedInventory r = row("kg", "100", "20", "-30");
        r.setAvailableQuantity(new BigDecimal("80"));
        locked(r);
        when(reportRepo.sumPendingInputBySourceWipNo(FACTORY, ANCHOR, null)).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> svc.validateSourceWip(FACTORY, ANCHOR, new BigDecimal("60"), "kg", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("领用量超过半成品余额");
        assertThat(svc.validateSourceWip(FACTORY, ANCHOR, new BigDecimal("50"), "kg", null)).isSameAs(r);
    }

    @Test
    @DisplayName("🔴 驳回报工: 已发生盘亏 −30 时退回全部产出会让余额变 −30 ⇒ 拒绝, 行与流水不动")
    void reverseReportPostingRefusesNegativeBalanceAfterStocktakeLoss() {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(1786L);
        task.setFactoryId(FACTORY);
        task.setProductionBatchId(10759L);
        task.setProcessOrder(2);
        task.setProductTypeId("PT1");
        ProductionReport report = new ProductionReport();
        report.setId(555L);
        report.setFactoryId(FACTORY);
        report.setOutputQuantity(new BigDecimal("100"));
        SemiFinishedInventory r = row("kg", "100", "0", "-30");
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.of(r));

        assertThatThrownBy(() -> svc.reverseReportPosting(FACTORY, report, task, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("盘亏 30");
        assertThat(r.getProducedQuantity()).isEqualByComparingTo("100");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("70");
        verify(wipRepo, never()).save(any());
        verify(txnRepo, never()).save(any());
    }

    @Test
    @DisplayName("阴性对照: 驳回时无盘点修正 ⇒ 照常退回, 余额 0")
    void reverseReportPostingStillWorksWithoutAdjustment() {
        WorkProcessTask task = new WorkProcessTask();
        task.setId(1786L);
        task.setFactoryId(FACTORY);
        task.setProductionBatchId(10759L);
        task.setProcessOrder(2);
        task.setProductTypeId("PT1");
        ProductionReport report = new ProductionReport();
        report.setId(556L);
        report.setFactoryId(FACTORY);
        report.setOutputQuantity(new BigDecimal("100"));
        SemiFinishedInventory r = row("kg", "100", "0", "0");
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.of(r));

        svc.reverseReportPosting(FACTORY, report, task, 1L);

        assertThat(r.getProducedQuantity()).isEqualByComparingTo("0");
        assertThat(r.getAvailableQuantity()).isEqualByComparingTo("0");
    }

    // ── 流水构造 (报工领用 OUT) ───────────────────────────────────────────────

    @Test
    @DisplayName("报工领用 OUT 流水的 balanceAfter 纳入盘点修正 (与库存行写回同公式)")
    void consumptionRowBalanceAfterIncludesAdjustment() {
        SemiFinishedInventory r = row("kg", "100", "20", "-30");
        ProductionReport report = new ProductionReport();
        report.setId(1L);
        WorkProcessTask task = new WorkProcessTask();
        task.setId(2L);

        SemiFinishedInventoryTransaction out = WipLedgerEntries.consumptionRow(
                r, new BigDecimal("10"), report, task, 1L);

        assertThat(out.getBalanceAfter()).isEqualByComparingTo("40");
    }
}
