package com.cretas.aims.service.wip.impl;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.ProductCategory;
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
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.service.quality.release.QcInspectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 质检放行 —— 半成品 (常驻半成品库 SFI) 的两条领用路径 + 产出入账扣留。
 *
 * <p>每个阻断点三件套: 待检 → 拒 (断言<b>本功能的</b> errorCode) / 已放行 → 通过 /
 * 没开「需检」的行 (inspectionStatus = null) → 完全不受影响 (阴性对照)。
 *
 * <p>跑的是<b>产品真实入口</b>: App 报工领用走 {@code validateSourceWip} (YieldReportServiceImpl 提交与审批都调它),
 * 逐道录入的 SFI 投料在小结时走 {@code consumeClerkSemiStrict}。策略用的是真的 {@link QcInspectionPolicy}
 * (只把产品仓储层 mock 掉), 「需检」开关真的从 {@link ProductType} 上读。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检放行 · 半成品领用闸 (App 报工 / 逐道录入小结) + 产出扣留")
class QcReleaseSemiDrawContractTest {

    private static final String FACTORY = "F-QC";

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

    @InjectMocks
    private WipInventoryServiceImpl service;

    @BeforeEach
    void wirePolicy() {
        // 生产里由 Spring 注入; 这里注入真的策略, 只 mock 它读产品的仓储层。
        ReflectionTestUtils.setField(service, "qcInspectionPolicy", new QcInspectionPolicy(productTypeRepo));
        when(reportRepo.sumPendingInputBySourceWipNo(anyString(), anyString(), any())).thenReturn(BigDecimal.ZERO);
    }

    private static SemiFinishedInventory sfi(String no, String inspectionStatus) {
        return SemiFinishedInventory.builder()
                .id(11L)
                .factoryId(FACTORY)
                .intermediateBatchNo(no)
                .producedQuantity(new BigDecimal("50"))
                .consumedQuantity(BigDecimal.ZERO)
                .availableQuantity(new BigDecimal("50"))
                .unit("kg")
                .inspectionStatus(inspectionStatus)
                .build();
    }

    private void stubLockedRow(SemiFinishedInventory row) {
        when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, row.getIntermediateBatchNo()))
                .thenReturn(Optional.of(row));
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ───────────────────────── App 报工领用 (validateSourceWip) ─────────────────────────

    @Nested
    @DisplayName("App 报工领用上道半成品")
    class AppDraw {

        @Test
        @DisplayName("待检 → 拒绝, errorCode=QC_SEMI_PENDING_INSPECTION")
        void pendingIsRejected() {
            stubLockedRow(sfi("WIP-P", SemiFinishedInventory.InspectionStatus.PENDING));
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.validateSourceWip(FACTORY, "WIP-P", new BigDecimal("10"), "kg", null));
            assertEquals(QcInspectionGuard.SEMI_PENDING, ex.getErrorCode());
            assertEquals(409, ex.getCode());
        }

        @Test
        @DisplayName("审查高 2: 部分待检 (50 里 20 待检) → 领 30 通过, 领 31 拒")
        void partialPendingIsQuantityLevel() {
            SemiFinishedInventory row = sfi("WIP-PART", SemiFinishedInventory.InspectionStatus.PENDING);
            row.setInspectionPendingQuantity(new BigDecimal("20"));
            stubLockedRow(row);
            assertEquals(row, service.validateSourceWip(FACTORY, "WIP-PART", new BigDecimal("30"), "kg", null));
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.validateSourceWip(FACTORY, "WIP-PART", new BigDecimal("31"), "kg", null));
            assertEquals(QcInspectionGuard.SEMI_PENDING, ex.getErrorCode());
        }

        @Test
        @DisplayName("不合格 → 拒绝, errorCode=QC_SEMI_REJECTED")
        void rejectedIsRejected() {
            stubLockedRow(sfi("WIP-R", SemiFinishedInventory.InspectionStatus.REJECTED));
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.validateSourceWip(FACTORY, "WIP-R", new BigDecimal("10"), "kg", null));
            assertEquals(QcInspectionGuard.SEMI_REJECTED, ex.getErrorCode());
        }

        @Test
        @DisplayName("已放行 → 通过")
        void releasedIsAllowed() {
            SemiFinishedInventory row = sfi("WIP-OK", SemiFinishedInventory.InspectionStatus.RELEASED);
            stubLockedRow(row);
            assertEquals(row, service.validateSourceWip(FACTORY, "WIP-OK", new BigDecimal("10"), "kg", null));
        }

        @Test
        @DisplayName("阴性对照: 没开需检 (inspectionStatus=null) → 与以前一样通过")
        void noSwitchIsUnaffected() {
            SemiFinishedInventory row = sfi("WIP-LEGACY", null);
            stubLockedRow(row);
            assertEquals(row, service.validateSourceWip(FACTORY, "WIP-LEGACY", new BigDecimal("10"), "kg", null));
        }
    }

    // ───────────────────────── 逐道录入小结扣减 (consumeClerkSemiStrict) ─────────────────────────

    @Nested
    @DisplayName("逐道录入 SFI 投料 (小结时严格扣减)")
    class WebSettleDraw {

        @Test
        @DisplayName("待检 → 拒绝, errorCode=QC_SEMI_PENDING_INSPECTION, 一克都不扣")
        void pendingIsRejected() {
            SemiFinishedInventory row = sfi("CLK-SEMI-P", SemiFinishedInventory.InspectionStatus.PENDING);
            stubLockedRow(row);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.consumeClerkSemiStrict(FACTORY, "CLK-SEMI-P", new BigDecimal("5")));
            assertEquals(QcInspectionGuard.SEMI_PENDING, ex.getErrorCode());
            assertEquals(0, BigDecimal.ZERO.compareTo(row.getConsumedQuantity()));
            verify(wipRepo, never()).save(any(SemiFinishedInventory.class));
        }

        @Test
        @DisplayName("已放行 → 正常扣减")
        void releasedIsDeducted() {
            SemiFinishedInventory row = sfi("CLK-SEMI-OK", SemiFinishedInventory.InspectionStatus.RELEASED);
            stubLockedRow(row);
            BigDecimal drawn = service.consumeClerkSemiStrict(FACTORY, "CLK-SEMI-OK", new BigDecimal("5"));
            assertEquals(0, new BigDecimal("5").compareTo(drawn));
            assertEquals(0, new BigDecimal("45").compareTo(row.getAvailableQuantity()));
        }

        @Test
        @DisplayName("阴性对照: 没开需检 → 正常扣减")
        void noSwitchIsUnaffected() {
            SemiFinishedInventory row = sfi("CLK-SEMI-LEGACY", null);
            stubLockedRow(row);
            BigDecimal drawn = service.consumeClerkSemiStrict(FACTORY, "CLK-SEMI-LEGACY", new BigDecimal("5"));
            assertEquals(0, new BigDecimal("5").compareTo(drawn));
        }
    }

    // ───────────────────────── 产出入账: 需检 ⇒ 待检 ─────────────────────────

    @Nested
    @DisplayName("产出入账")
    class OutputHold {

        private ProductType product(String id, String code, boolean inspectionRequired, String category) {
            ProductType pt = new ProductType();
            pt.setId(id);
            pt.setCode(code);
            pt.setFactoryId(FACTORY);
            pt.setProductCategory(category);
            pt.setInspectionRequired(inspectionRequired);
            return pt;
        }

        /** 逐道录入保存/小结入半成品库 (postClerkOutput), 返回写库那一刻的行。 */
        private SemiFinishedInventory postClerk(String productTypeId, SemiFinishedInventory existing) {
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "CLK-SEMI-1"))
                    .thenReturn(Optional.of(existing));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            service.postClerkOutput(FACTORY, "CLK-SEMI-1", productTypeId, new BigDecimal("20"), "kg",
                    null, null, 2);
            ArgumentCaptor<SemiFinishedInventory> saved = ArgumentCaptor.forClass(SemiFinishedInventory.class);
            verify(wipRepo).save(saved.capture());
            return saved.getValue();
        }

        @Test
        @DisplayName("逐道录入: 需检半成品的产出 → PENDING")
        void webOutputOfRequiredSemiIsPending() {
            when(productTypeRepo.findByIdAndFactoryId("PT-SEMI-Q", FACTORY))
                    .thenReturn(Optional.of(product("PT-SEMI-Q", "SEMI-Q", true, ProductCategory.SEMI_FINISHED)));
            SemiFinishedInventory row = postClerk("PT-SEMI-Q", sfi("CLK-SEMI-1", null));
            System.out.println("WEB_OUTPUT_INSPECTION_STATUS=" + row.getInspectionStatus());
            assertEquals(SemiFinishedInventory.InspectionStatus.PENDING, row.getInspectionStatus());
        }

        @Test
        @DisplayName("审查高 2: 已放行 50 的行又入 20 需检产出 → 只有这 20 待检, 已放行的 50 照常可领")
        void newOutputHoldsOnlyItsOwnQuantity() {
            when(productTypeRepo.findByIdAndFactoryId("PT-SEMI-Q", FACTORY))
                    .thenReturn(Optional.of(product("PT-SEMI-Q", "SEMI-Q", true, ProductCategory.SEMI_FINISHED)));
            SemiFinishedInventory row = postClerk("PT-SEMI-Q", sfi("CLK-SEMI-1", null));
            System.out.println("QTY_HOLD pending=" + row.getInspectionPendingQuantity()
                    + " balance=" + row.computeBalance() + " drawable=" + row.drawableBalance());
            assertEquals(SemiFinishedInventory.InspectionStatus.PENDING, row.getInspectionStatus());
            assertEquals(0, new BigDecimal("20").compareTo(row.getInspectionPendingQuantity()));
            assertEquals(0, new BigDecimal("50").compareTo(row.drawableBalance()));
            // 领 50 (已放行的那部分) 通过; 领 51 (碰到待检的 20) 被拒
            QcInspectionGuard.assertSemiDrawable(row, new BigDecimal("50"), "t");
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> QcInspectionGuard.assertSemiDrawable(row, new BigDecimal("51"), "t"));
            assertEquals(QcInspectionGuard.SEMI_PENDING, ex.getErrorCode());
        }

        @Test
        @DisplayName("审查高 2: 旧数据整行 REJECTED 又来新产出 → 回到 PENDING, 整个余额待检 (旧不合格量不被洗成可领)")
        void legacyRejectedRowRePendsWholeBalance() {
            when(productTypeRepo.findByIdAndFactoryId("PT-SEMI-Q", FACTORY))
                    .thenReturn(Optional.of(product("PT-SEMI-Q", "SEMI-Q", true, ProductCategory.SEMI_FINISHED)));
            SemiFinishedInventory row = postClerk("PT-SEMI-Q",
                    sfi("CLK-SEMI-1", SemiFinishedInventory.InspectionStatus.REJECTED));
            assertEquals(SemiFinishedInventory.InspectionStatus.PENDING, row.getInspectionStatus());
            assertEquals(0, new BigDecimal("70").compareTo(row.getInspectionPendingQuantity()));
            assertEquals(0, BigDecimal.ZERO.compareTo(row.drawableBalance()));
        }

        @Test
        @DisplayName("审查高 2: 编辑 (撤销 20 + 重入 20) 不让待检翻倍")
        void editDoesNotDoublePending() {
            when(productTypeRepo.findByIdAndFactoryId("PT-SEMI-Q", FACTORY))
                    .thenReturn(Optional.of(product("PT-SEMI-Q", "SEMI-Q", true, ProductCategory.SEMI_FINISHED)));
            // 第二轮审查 H3: 撤销只解除「这一笔」的待检 —— 按逐道录入凭据认笔 (分笔账用内存版)
            com.cretas.aims.service.quality.release.InMemoryHoldLots lots =
                    new com.cretas.aims.service.quality.release.InMemoryHoldLots();
            ReflectionTestUtils.setField(service, "semiQcHoldLots", lots.service);
            SemiFinishedInventory row = sfi("CLK-SEMI-1", null);
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, "CLK-SEMI-1"))
                    .thenReturn(Optional.of(row));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            String ref = "PS:PLAN:row-1";
            service.postClerkOutput(FACTORY, "CLK-SEMI-1", "PT-SEMI-Q", new BigDecimal("20"), "kg", null, null, 2,
                    false, ref);
            service.reverseClerkOutput(FACTORY, "CLK-SEMI-1", new BigDecimal("20"), null, 1L, ref);
            service.postClerkOutput(FACTORY, "CLK-SEMI-1", "PT-SEMI-Q", new BigDecimal("20"), "kg", null, null, 2,
                    false, ref);
            assertEquals(0, new BigDecimal("20").compareTo(row.getInspectionPendingQuantity()));
            assertEquals(0, new BigDecimal("50").compareTo(row.drawableBalance()));
        }

        @Test
        @DisplayName("阴性对照: 没开需检的产品 → inspectionStatus 保持 null")
        void webOutputWithoutSwitchStaysNull() {
            when(productTypeRepo.findByIdAndFactoryId("PT-SEMI-N", FACTORY))
                    .thenReturn(Optional.of(product("PT-SEMI-N", "SEMI-N", false, ProductCategory.SEMI_FINISHED)));
            assertNull(postClerk("PT-SEMI-N", sfi("CLK-SEMI-1", null)).getInspectionStatus());
        }

        @Test
        @DisplayName("App 报工半成品产出: 按 semiCode 找到需检半成品 → PENDING; 未开 → null")
        void appSemiOutputUsesSemiCodeProduct() {
            when(productTypeRepo.findByFactoryIdAndCode(FACTORY, "SEMI-Q"))
                    .thenReturn(Optional.of(product("PT-SEMI-Q", "SEMI-Q", true, ProductCategory.SEMI_FINISHED)));
            when(productTypeRepo.findByFactoryIdAndCode(FACTORY, "SEMI-N"))
                    .thenReturn(Optional.of(product("PT-SEMI-N", "SEMI-N", false, ProductCategory.SEMI_FINISHED)));
            assertEquals(SemiFinishedInventory.InspectionStatus.PENDING, postAppSemi("SEMI-Q", 701L));
            assertNull(postAppSemi("SEMI-N", 702L));
        }

        @Test
        @DisplayName("阴性对照: 成品 (FINISHED_PRODUCT) 开了需检, 它的半成品形态产出不扣 (成品在入库时扣)")
        void finishedProductSwitchDoesNotHoldSemiRows() {
            when(productTypeRepo.findByFactoryIdAndCode(FACTORY, "FG-Q"))
                    .thenReturn(Optional.of(product("PT-FG-Q", "FG-Q", true, ProductCategory.FINISHED_PRODUCT)));
            assertNull(postAppSemi("FG-Q", 703L));
        }

        private String postAppSemi(String semiCode, Long reportId) {
            WorkProcessTask task = WorkProcessTask.builder()
                    .id(8001L).factoryId(FACTORY).productionBatchId(9100L)
                    .productTypeId("PT-FG").processOrder(2).plannedUnit("kg").build();
            ProductionReport report = ProductionReport.builder()
                    .factoryId(FACTORY).id(reportId).batchId(9100L).workProcessTaskId(8001L)
                    .outputKind("SEMI").semiCode(semiCode)
                    .semiOutputQuantity(new BigDecimal("30")).semiOutputUnit("kg")
                    .laborCost(new BigDecimal("30")).build();
            SemiFinishedInventory row = sfi(semiCode, null);
            row.setProducedQuantity(BigDecimal.ZERO);
            row.setAvailableQuantity(BigDecimal.ZERO);
            when(txnRepo.findByFactoryIdAndReportId(FACTORY, reportId)).thenReturn(Collections.emptyList());
            when(wipRepo.findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, semiCode))
                    .thenReturn(Optional.of(row));
            when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
            when(txnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
            service.postApprovedOutput(FACTORY, report, task, 20L);
            return row.getInspectionStatus();
        }
    }
}
