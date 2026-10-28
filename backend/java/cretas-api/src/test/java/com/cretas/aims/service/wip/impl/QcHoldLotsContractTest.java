package com.cretas.aims.service.wip.impl;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.foodsafety.ccp.CcpMonitoringService;
import com.cretas.aims.service.foodsafety.ccp.CcpOpenDeviationLookup;
import com.cretas.aims.service.quality.release.InMemoryHoldLots;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import com.cretas.aims.service.quality.release.QcReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 第二轮审查 H2 / H3 / M1 / L1 —— 待检分笔账: 撤销、驳回、数量更正某一笔时只动「这一笔还没放行」的待检。
 *
 * <p>跑的是真的 {@link WipInventoryServiceImpl} (逐道录入入账 / 撤销、App 报工驳回、报工数量更正) +
 * 真的 {@link QcReleaseService} 放行 + 真的分笔账口径 ({@code SemiQcHoldLots}, 只把仓储换成内存版)。
 * 库存行与流水也放在内存里, 让同一行在多次调用之间保持状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检分笔账: 撤销 / 驳回 / 更正只解除这一笔自己未放行的待检")
class QcHoldLotsContractTest {

    private static final String F = "F-LOT";
    private static final String ANCHOR = "CLK-SEMI-PLANLOT1-PTSEMIQ1";
    private static final String REF_A = "PS:PLAN-LOT:row-A";
    private static final String REF_B = "PS:PLAN-LOT:row-B";

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductionWorkflowInstanceRepository workflowInstanceRepo;
    @Mock private WorkflowTaskPortRepository workflowTaskPortRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CcpOpenDeviationLookup deviationLookup;
    @Mock private CcpMonitoringService ccpMonitoringService;

    @org.mockito.Mock
    private com.cretas.aims.service.processentry.IntermediateOwnershipGuard ownershipGuard;
    @InjectMocks
    private WipInventoryServiceImpl service;

    private InMemoryHoldLots lots;
    private QcReleaseService releaseService;
    private final List<SemiFinishedInventory> rows = new ArrayList<>();
    private final List<SemiFinishedInventoryTransaction> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        QcInspectionPolicy policy = new QcInspectionPolicy(productTypeRepo);
        policy.setDeviationLookup(deviationLookup);
        ReflectionTestUtils.setField(service, "qcInspectionPolicy", policy);
        lots = new InMemoryHoldLots();
        ReflectionTestUtils.setField(service, "semiQcHoldLots", lots.service);
        ReflectionTestUtils.setField(service, "ccpMonitoringService", ccpMonitoringService);

        ProductType semi = new ProductType();
        semi.setId("PT-SEMI-Q");
        semi.setCode("SEMI-Q");
        semi.setFactoryId(F);
        semi.setProductCategory(ProductCategory.SEMI_FINISHED);
        semi.setInspectionRequired(true);
        when(productTypeRepo.findByIdAndFactoryId("PT-SEMI-Q", F)).thenReturn(Optional.of(semi));
        when(reportRepo.sumPendingInputBySourceWipNo(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);

        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(eq(F), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(r -> r.getIntermediateBatchNo().equals(inv.getArgument(1))).findFirst());
        when(wipRepo.findByIdAndFactoryIdForUpdate(anyLong(), eq(F))).thenAnswer(inv -> rows.stream()
                .filter(r -> Objects.equals(r.getId(), inv.getArgument(0))).findFirst());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> {
            SemiFinishedInventoryTransaction t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(5000L + ledger.size());
            }
            ledger.add(t);
            return t;
        });
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(t -> Objects.equals(t.getSemiFinishedId(), inv.getArgument(0))).toList());
        when(txnRepo.findByFactoryIdAndReportId(eq(F), anyLong())).thenAnswer(inv -> ledger.stream()
                .filter(t -> Objects.equals(t.getReportId(), inv.getArgument(1))).toList());

        QcReleaseRecordRepository recordRepo = mock(QcReleaseRecordRepository.class);
        when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        releaseService = new QcReleaseService(wipRepo, mock(MaterialBatchRepository.class),
                mock(FinishedGoodsBatchRepository.class), productTypeRepo, recordRepo);
        releaseService.setSemiTxnRepo(txnRepo);
        releaseService.setSemiQcHoldLots(lots.service);
    }

    private SemiFinishedInventory row(long id, String no, String produced) {
        SemiFinishedInventory r = SemiFinishedInventory.builder()
                .id(id).factoryId(F).intermediateBatchNo(no).productTypeId("PT-SEMI-Q").unit("kg")
                .producedQuantity(new BigDecimal(produced)).consumedQuantity(BigDecimal.ZERO)
                .adjustmentQuantity(BigDecimal.ZERO).availableQuantity(new BigDecimal(produced))
                .status(SemiFinishedInventory.Status.AVAILABLE).build();
        rows.add(r);
        return r;
    }

    private void postClerk(String qty, String ref) {
        service.postClerkOutput(F, ANCHOR, "PT-SEMI-Q", new BigDecimal(qty), "kg", null, null, 2, false, ref);
    }

    private void release(SemiFinishedInventory r) {
        releaseService.decide(F, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.SEMI_FINISHED, String.valueOf(r.getId()), "RELEASED", "合格"), 7L, null);
    }

    private static void show(String tag, SemiFinishedInventory r) {
        System.out.println(tag + " balance=" + r.computeBalance() + " pending=" + r.getInspectionPendingQuantity()
                + " drawable=" + r.drawableBalance());
    }

    // ─────────────────────────── H3: 逐道录入锚点行 ───────────────────────────

    @Nested
    @DisplayName("H3 逐道录入锚点行累加多笔")
    class ClerkAnchor {

        private SemiFinishedInventory aReleasedThenBPending() {
            SemiFinishedInventory r = row(31L, ANCHOR, "0");
            postClerk("100", REF_A);
            release(r);                 // A 的 100 已放行
            postClerk("40", REF_B);     // B 的 40 待检
            assertThat(r.drawableBalance()).isEqualByComparingTo("100");
            return r;
        }

        @Test
        @DisplayName("删除已放行的 A → B 的 40 仍待检 (可领 0), 不被 A 抵消")
        void deleteReleasedEntryKeepsOthersPending() {
            SemiFinishedInventory r = aReleasedThenBPending();
            service.reverseClerkOutput(F, ANCHOR, new BigDecimal("100"), null, 1L, REF_A);
            show("H3-delete", r);
            assertThat(r.computeBalance()).isEqualByComparingTo("40");
            assertThat(r.drawableBalance()).isEqualByComparingTo("0");
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("编辑 A (撤 100 再入 100) → 可领量里不包含 B (A 重新入账也要重新放行)")
        void editReleasedEntryDoesNotFreeOthers() {
            SemiFinishedInventory r = aReleasedThenBPending();
            service.reverseClerkOutput(F, ANCHOR, new BigDecimal("100"), null, 1L, REF_A);
            postClerk("100", REF_A);
            show("H3-edit", r);
            assertThat(r.computeBalance()).isEqualByComparingTo("140");
            assertThat(r.drawableBalance()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("删除自己还在待检的 B → 只解除 B 的 40, A 已放行的 100 照常可领 (不误挡)")
        void deleteOwnPendingEntryFreesOnlyItself() {
            SemiFinishedInventory r = aReleasedThenBPending();
            service.reverseClerkOutput(F, ANCHOR, new BigDecimal("40"), null, 1L, REF_B);
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("0");
            assertThat(r.drawableBalance()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("成本重分摊 (同一行撤再入) → 待检不翻倍, 已放行的 100 不被挡")
        void reallocationIsNetZero() {
            SemiFinishedInventory r = aReleasedThenBPending();
            service.reverseClerkOutput(F, ANCHOR, new BigDecimal("40"), null, 1L, REF_B);
            postClerk("40", REF_B);
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("40");
            assertThat(r.drawableBalance()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("阴性对照 (保守): 认不出是哪一笔的撤销 (无凭据) → 待检一律不减, 只按余额封顶")
        void reverseWithoutRefNeverFreesHolds() {
            SemiFinishedInventory r = aReleasedThenBPending();
            service.reverseClerkOutput(F, ANCHOR, new BigDecimal("40"), null, 1L);
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("40");
            assertThat(r.drawableBalance()).isEqualByComparingTo("60");
        }
    }

    // ─────────────────────────── H2 / M1 / L1: App 报工 ───────────────────────────

    @Nested
    @DisplayName("App 报工: 数量更正 (H2) / 驳回 (M1) / 报错 (L1)")
    class AppReport {

        private WorkProcessTask task() {
            return WorkProcessTask.builder().id(8001L).factoryId(F).productionBatchId(9100L)
                    .productTypeId("PT-FG").processOrder(2).plannedUnit("kg").build();
        }

        private static final String WIP_NO = "PT-FG-B9100-S2-8001";

        private ProductionReport report(long id) {
            return ProductionReport.builder().id(id).factoryId(F).batchId(9100L).workProcessTaskId(8001L)
                    .outputKind("FINISHED").outputQuantity(new BigDecimal("100")).outputUnit("kg").build();
        }

        /** 报工 R 的 100 入账时因 CCP 偏离整笔扣成待检 (分笔账记一笔)。 */
        private SemiFinishedInventory heldRow(long reportId) {
            SemiFinishedInventory r = row(41L, WIP_NO, "100");
            r.setInspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING);
            r.setInspectionPendingQuantity(new BigDecimal("100"));
            ledger.add(SemiFinishedInventoryTransaction.builder().id(4100L).factoryId(F).semiFinishedId(41L)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                    .quantity(new BigDecimal("100")).reportId(reportId).build());
            lots.service.record(r, new BigDecimal("100"), reportId, null);
            return r;
        }

        @Test
        @DisplayName("H2 余额 100 全部待检, 更正到 120 (偏离仍在) → 多出的 20 也进待检, 可领 0")
        void correctionIncreaseHoldsTheDelta() {
            SemiFinishedInventory r = heldRow(701L);
            when(deviationLookup.hasOpen(eq(F), anyCollection(), any(), any())).thenReturn(true);
            service.correctApprovedOutputQuantity(F, report(701L), task(), new BigDecimal("100"),
                    new BigDecimal("120"), "corr-1", 7L);
            show("H2-up", r);
            assertThat(r.computeBalance()).isEqualByComparingTo("120");
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("120");
            assertThat(r.drawableBalance()).isEqualByComparingTo("0");
            assertThat(lots.openTotal(41L)).isEqualByComparingTo("120");
        }

        @Test
        @DisplayName("阴性对照: 没有偏离、产品不需检的行更正到 120 → 不进待检, 120 全可领")
        void correctionWithoutHoldStaysFree() {
            SemiFinishedInventory r = row(41L, WIP_NO, "100");
            ledger.add(SemiFinishedInventoryTransaction.builder().id(4101L).factoryId(F).semiFinishedId(41L)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                    .quantity(new BigDecimal("100")).reportId(702L).build());
            service.correctApprovedOutputQuantity(F, report(702L), task(), new BigDecimal("100"),
                    new BigDecimal("120"), "corr-2", 7L);
            assertThat(r.getInspectionStatus()).isNull();
            assertThat(r.drawableBalance()).isEqualByComparingTo("120");
        }

        @Test
        @DisplayName("H2 改小 (100→70): 只解除这次报工自己的 30, 其余仍待检")
        void correctionDecreaseUnwindsOwnHoldOnly() {
            SemiFinishedInventory r = heldRow(703L);
            service.correctApprovedOutputQuantity(F, report(703L), task(), new BigDecimal("100"),
                    new BigDecimal("70"), "corr-3", 7L);
            assertThat(r.computeBalance()).isEqualByComparingTo("70");
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("70");
            assertThat(r.drawableBalance()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("M1 驳回还在待检的报工 (40) → 只解除它的 40, 已放行的 100 不再被误挡; 它的 CCP 偏离作废")
        void rejectUnwindsOwnHoldAndVoidsCcp() {
            SemiFinishedInventory r = row(41L, WIP_NO, "140");
            r.setInspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING);
            r.setInspectionPendingQuantity(new BigDecimal("40"));
            lots.service.record(r, new BigDecimal("40"), 801L, null);
            ProductionReport rep = report(801L);
            rep.setOutputQuantity(new BigDecimal("40"));
            service.reverseReportPosting(F, rep, task(), 7L);
            show("M1-reject", r);
            assertThat(r.computeBalance()).isEqualByComparingTo("100");
            assertThat(r.drawableBalance()).isEqualByComparingTo("100");
            verify(ccpMonitoringService).voidDeviationsOfReport(eq(F), eq("YR:801"), eq(7L), anyString());
        }

        @Test
        @DisplayName("阴性对照: 驳回已放行的报工 → 别人的待检 40 一点不减")
        void rejectReleasedReportKeepsOthersPending() {
            SemiFinishedInventory r = row(41L, WIP_NO, "140");
            r.setInspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING);
            r.setInspectionPendingQuantity(new BigDecimal("40"));
            lots.service.record(r, new BigDecimal("40"), 802L, null);    // 别人的 (报工 802)
            ProductionReport rep = report(803L);                            // 驳回 803 (已放行, 无分笔)
            service.reverseReportPosting(F, rep, task(), 7L);
            assertThat(r.computeBalance()).isEqualByComparingTo("40");
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("40");
            assertThat(r.drawableBalance()).isEqualByComparingTo("0");
        }

        /**
         * 第三轮审查 M-1: SEMI 报工的半成品产出入在 semiCode 行 (按产品码跨批次累加), 不在任务在制行。
         * 行 61 上: 别的报工 900 的 30 待检 + 这次报工 810 的 50 待检。
         */
        private SemiFinishedInventory semiCodeRow() {
            SemiFinishedInventory r = row(61L, "SEMI-Q", "80");
            r.setAccumulatedCost(new BigDecimal("160"));
            r.setUnitCost(new BigDecimal("2"));
            r.setInspectionStatus(SemiFinishedInventory.InspectionStatus.PENDING);
            r.setInspectionPendingQuantity(new BigDecimal("80"));
            lots.service.record(r, new BigDecimal("30"), 900L, null, 9100L);
            lots.service.record(r, new BigDecimal("50"), 810L, null, 9100L);
            ledger.add(SemiFinishedInventoryTransaction.builder().id(6100L).factoryId(F).semiFinishedId(61L)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.IN)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.PRODUCTION_OUTPUT)
                    .quantity(new BigDecimal("50")).unitCostAtTxn(new BigDecimal("2")).reportId(810L).build());
            return r;
        }

        private ProductionReport semiReport() {
            return ProductionReport.builder().id(810L).factoryId(F).batchId(9100L).workProcessTaskId(8001L)
                    .outputKind("SEMI").semiCode("SEMI-Q").semiOutputQuantity(new BigDecimal("50"))
                    .outputQuantity(BigDecimal.ZERO).outputUnit("kg").build();
        }

        @Test
        @DisplayName("M-1 驳回 SEMI 报工 → semiCode 行余额减 50、这笔的分笔清掉、待检减 50; 别的报工的 30 不动")
        void rejectSemiReportReversesSemiCodeRow() {
            SemiFinishedInventory r = semiCodeRow();
            service.reverseReportPosting(F, semiReport(), task(), 7L);
            show("M-1", r);
            assertThat(r.computeBalance()).isEqualByComparingTo("30");
            assertThat(r.getInspectionPendingQuantity()).isEqualByComparingTo("30");
            assertThat(r.getAccumulatedCost()).isEqualByComparingTo("60");
            assertThat(lots.lots).filteredOn(l -> l.getReportId() == 810L).singleElement()
                    .satisfies(l -> assertThat(l.getQuantityOpen()).isEqualByComparingTo("0"));
            assertThat(lots.lots).filteredOn(l -> l.getReportId() == 900L).singleElement()
                    .satisfies(l -> assertThat(l.getQuantityOpen()).isEqualByComparingTo("30"));
            assertThat(ledger).filteredOn(t -> SemiFinishedInventoryTransaction.TxnType.REVERSE.equals(t.getTxnType())
                            && Objects.equals(t.getSemiFinishedId(), 61L))
                    .singleElement().satisfies(t -> assertThat(t.getQuantity()).isEqualByComparingTo("-50"));
        }

        @Test
        @DisplayName("M-1 阴性对照: 这笔的货已被领走 (退完余额会变负) → 拒绝并说明, 行与分笔都不动")
        void rejectSemiReportRefusedWhenAlreadyDrawn() {
            SemiFinishedInventory r = semiCodeRow();
            r.setConsumedQuantity(new BigDecimal("40"));
            r.refreshAvailableQuantity();   // 余 40, 退 50 会变负
            assertThatThrownBy(() -> service.reverseReportPosting(F, semiReport(), task(), 7L))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo("WIP_REVERSE_SEMI_CONSUMED");
                        assertThat(e.getMessage()).contains("已被下一道领用 40");
                    });
            assertThat(r.getProducedQuantity()).isEqualByComparingTo("80");
            assertThat(lots.openTotal(61L)).isEqualByComparingTo("80");
        }

        @Test
        @DisplayName("L1 质检判不合格转出的量不说成「被下一道领用」, 并写清怎么处理")
        void qcRejectedConsumptionIsNamedSeparately() {
            SemiFinishedInventory r = row(41L, WIP_NO, "100");
            r.setConsumedQuantity(new BigDecimal("30"));
            r.refreshAvailableQuantity();
            ledger.add(SemiFinishedInventoryTransaction.builder().id(4102L).factoryId(F).semiFinishedId(41L)
                    .txnType(SemiFinishedInventoryTransaction.TxnType.OUT)
                    .sourceType(SemiFinishedInventoryTransaction.SourceType.QC_REJECT)
                    .quantity(new BigDecimal("-30")).build());
            assertThatThrownBy(() -> service.reverseReportPosting(F, report(804L), task(), 7L))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getMessage()).contains("质检判不合格转出 30").doesNotContain("下一道领用");
                        assertThat(e.getErrorCode()).isEqualTo("WIP_REVERSE_QC_REJECTED");
                        assertThat(e.getActionHint()).contains("半成品盘点");
                    });
        }

        @Test
        @DisplayName("阴性对照: 真被下一道领用的量照旧说「已被下一道领用」")
        void downstreamConsumptionKeepsOldWording() {
            SemiFinishedInventory r = row(41L, WIP_NO, "100");
            r.setConsumedQuantity(new BigDecimal("30"));
            r.refreshAvailableQuantity();
            assertThatThrownBy(() -> service.reverseReportPosting(F, report(805L), task(), 7L))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getMessage()).contains("已被下一道领用 30").doesNotContain("质检");
                        assertThat(e.getErrorCode()).isNull();
                    });
        }
    }
}
