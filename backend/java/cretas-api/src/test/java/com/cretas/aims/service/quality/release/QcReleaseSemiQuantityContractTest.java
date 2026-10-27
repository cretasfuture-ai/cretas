package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 审查高 2 —— 半成品行按<b>数量</b>放行 / 判不合格 (选的方案: 数量级待检, 不是整行状态)。
 *
 * <p>一行半成品库存按产品码跨批次累加: 已放行的存量 + 新入的待检量共处一行。
 * <ul>
 *   <li>判不合格: 只把待检的那部分从余额里转出 (QC_REJECT 出库流水), 已放行的照常可领, 行回到「无待检」;
 *       以后的新产出照常按需检进待检 —— 不存在「REJECTED 行永远放不出」;</li>
 *   <li>放行: 只清掉待检数量, 不动余额;</li>
 *   <li>转出缺流水仓储时整笔拒绝 —— 不允许「判了不合格却没转出」把不合格量洗回可领。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检放行 · 半成品按数量放行/判不合格")
class QcReleaseSemiQuantityContractTest {

    private static final String F = "F-QCQ";

    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private QcReleaseRecordRepository recordRepo;
    @Mock private SemiFinishedInventoryTransactionRepository semiTxnRepo;

    private QcReleaseService service;

    @BeforeEach
    void setUp() {
        service = new QcReleaseService(semiRepo, materialBatchRepo, fgRepo, productTypeRepo, recordRepo);
        service.setSemiTxnRepo(semiTxnRepo);
        when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiTxnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 70 kg 的一行, 其中 pending 待检 (null = 旧数据整行待检)。 */
    private SemiFinishedInventory row(String inspection, String pending, String produced) {
        SemiFinishedInventory s = SemiFinishedInventory.builder()
                .id(31L).factoryId(F).intermediateBatchNo("SEMI-Q").productTypeId("PT-S").unit("kg")
                .producedQuantity(new BigDecimal(produced)).consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal(produced))
                .status(SemiFinishedInventory.Status.AVAILABLE)
                .unitCost(new BigDecimal("2"))
                .inspectionStatus(inspection)
                .inspectionPendingQuantity(pending == null ? null : new BigDecimal(pending))
                .build();
        when(semiRepo.findByIdAndFactoryIdForUpdate(31L, F)).thenReturn(Optional.of(s));
        return s;
    }

    private QcReleaseRecord decide(String decision) {
        return service.decide(F, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.SEMI_FINISHED, "31", decision, "结论"), 7L, "质检员");
    }

    @Test
    @DisplayName("判不合格: 70 里待检 20 → 转出 20 (QC_REJECT 流水 -20), 余 50 照常可领, 行回到无待检")
    void rejectWritesOffOnlyThePendingQuantity() {
        SemiFinishedInventory s = row(SemiFinishedInventory.InspectionStatus.PENDING, "20", "70");
        QcReleaseRecord rec = decide("REJECTED");
        System.out.println("REJECT consumed=" + s.getConsumedQuantity() + " available=" + s.getAvailableQuantity()
                + " inspection=" + s.getInspectionStatus() + " pending=" + s.getInspectionPendingQuantity());

        assertThat(s.getConsumedQuantity()).isEqualByComparingTo("20");
        assertThat(s.getAvailableQuantity()).isEqualByComparingTo("50");
        assertThat(s.getInspectionStatus()).isNull();
        assertThat(s.getInspectionPendingQuantity()).isNull();
        assertThat(rec.getQuantity()).isEqualByComparingTo("20");
        ArgumentCaptor<SemiFinishedInventoryTransaction> txn = ArgumentCaptor.forClass(SemiFinishedInventoryTransaction.class);
        verify(semiTxnRepo).save(txn.capture());
        assertThat(txn.getValue().getTxnType()).isEqualTo(SemiFinishedInventoryTransaction.TxnType.OUT);
        assertThat(txn.getValue().getSourceType()).isEqualTo(SemiFinishedInventoryTransaction.SourceType.QC_REJECT);
        assertThat(txn.getValue().getQuantity()).isEqualByComparingTo("-20");
        assertThat(txn.getValue().getBalanceAfter()).isEqualByComparingTo("50");
        // 已放行的 50 照常可领 (不是「整行不合格永远领不出」)
        assertThatCode(() -> QcInspectionGuard.assertSemiDrawable(s, new BigDecimal("50"), "t"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("判不合格之后新产出 10 需检 → 只有这 10 进待检, 前面的 50 照常可领 (行没有卡死)")
    void newOutputAfterRejectRePendsOnlyItself() {
        SemiFinishedInventory s = row(SemiFinishedInventory.InspectionStatus.PENDING, "20", "70");
        decide("REJECTED");
        s.setProducedQuantity(s.getProducedQuantity().add(BigDecimal.TEN));
        s.refreshAvailableQuantity();
        QcInspectionPolicy.applySemiOutputHold(s, true, BigDecimal.TEN);

        assertThat(s.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
        assertThat(s.getInspectionPendingQuantity()).isEqualByComparingTo("10");
        assertThat(s.drawableBalance()).isEqualByComparingTo("50");
        assertThatThrownBy(() -> QcInspectionGuard.assertSemiDrawable(s, new BigDecimal("51"), "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.SEMI_PENDING));
    }

    @Test
    @DisplayName("旧数据整行待检 (待检数量为空) 判不合格 → 整个余额转出, 行耗尽")
    void legacyWholePendingRejectDepletes() {
        SemiFinishedInventory s = row(SemiFinishedInventory.InspectionStatus.PENDING, null, "30");
        QcReleaseRecord rec = decide("REJECTED");
        assertThat(rec.getQuantity()).isEqualByComparingTo("30");
        assertThat(s.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(s.getStatus()).isEqualTo(SemiFinishedInventory.Status.DEPLETED);
    }

    @Test
    @DisplayName("阴性对照 · 放行: 只清待检数量, 不转出、不写流水, 70 全部可领")
    void releaseClearsPendingWithoutWriteOff() {
        SemiFinishedInventory s = row(SemiFinishedInventory.InspectionStatus.PENDING, "20", "70");
        QcReleaseRecord rec = decide("RELEASED");
        assertThat(rec.getQuantity()).isEqualByComparingTo("20");
        assertThat(s.getConsumedQuantity()).isEqualByComparingTo("0");
        assertThat(s.getInspectionStatus()).isNull();
        assertThat(s.drawableBalance()).isEqualByComparingTo("70");
        verify(semiTxnRepo, never()).save(any());
    }

    @Test
    @DisplayName("没有待检数量的行 → 拒 (QC_RELEASE_NOT_PENDING), 不改任何东西")
    void nothingPendingIsRejected() {
        SemiFinishedInventory s = row(null, null, "70");
        assertThatThrownBy(() -> decide("REJECTED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_NOT_PENDING));
        assertThat(s.getConsumedQuantity()).isEqualByComparingTo("0");
        verify(semiRepo, never()).save(any());
    }

    @Test
    @DisplayName("转出缺流水仓储 → 整笔拒绝, 不出现「判了不合格却没转出」")
    void rejectWithoutLedgerFailsLoud() {
        service.setSemiTxnRepo(null);
        SemiFinishedInventory s = row(SemiFinishedInventory.InspectionStatus.PENDING, "20", "70");
        assertThatThrownBy(() -> decide("REJECTED")).isInstanceOf(BusinessException.class);
        assertThat(s.getInspectionStatus()).isEqualTo(SemiFinishedInventory.InspectionStatus.PENDING);
        verify(semiRepo, never()).save(any());
    }
}
