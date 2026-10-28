package com.cretas.aims.service.production;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.quality.release.QcInspectionGuard;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 审查 —— 结单 (settleProduction) 的两处:
 * <ul>
 *   <li>高 1: 结单领用过账 ({@code postConsumptionToInventory}, settleProduction 唯一的扣库步骤) 必须过质检闸 ——
 *       常驻半成品按数量判 (余额 − 待检), 在制批次 (sourceDocType=PRODUCTION_BATCH) 待检不能领。</li>
 *   <li>中高 4: 小结成品核验 ({@code validateInterimFinishedGoodsIdentity}) 把待检成品当成有效入库真值 ——
 *       没开需检的产品, 同计划有 CCP 偏离把成品扣成待检后, 结单照样能结 (零回归)。</li>
 * </ul>
 * 每条阻断都断言<b>本闸的</b>错误码, 并配一条同输入只改闸所看字段就通过的阴性对照。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检放行 · 结单领用闸 + 小结成品待检不挡结单")
class QcSettlementGuardsContractTest {

    private static final String FACTORY_ID = "F-QCS";
    private static final String PLAN_ID = "PLAN-QCS";

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper productionPlanMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private SemiFinishedInventoryTransactionRepository semiTxnRepo;
    @Mock private InventoryLowStockEventPublisher lowStockPublisher;

    private ProductionPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductionPlanServiceImpl(
                productionPlanRepository, productionBatchRepository,
                materialBatchRepository, materialConsumptionRepository, planBatchUsageRepository,
                productTypeRepository, productionPlanMapper, conversionRepository, schedulingService,
                productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "intermediateOwnershipGuard",
                org.mockito.Mockito.mock(com.cretas.aims.service.processentry.IntermediateOwnershipGuard.class));
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semiRepo);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryTransactionRepository", semiTxnRepo);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", lowStockPublisher);
        when(semiRepo.save(any(SemiFinishedInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(semiTxnRepo.save(any(SemiFinishedInventoryTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ProductionPlan plan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("P-QCS");
        plan.setProductTypeId("PT-1");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    /** 结单真正扣库的那一步 (settleProduction 在保存消耗行之后调它, 见 ProductionPlanServiceImpl#settleProduction)。 */
    private void post(ProductionSettlementConsumption line) {
        ReflectionTestUtils.invokeMethod(service, "postConsumptionToInventory",
                FACTORY_ID, plan(), List.of(line), 10L);
    }

    @Nested
    @DisplayName("高 1: 结单领用常驻半成品")
    class SemiDraw {

        private SemiFinishedInventory sfi(String inspection, String pendingQty) {
            SemiFinishedInventory s = SemiFinishedInventory.builder()
                    .id(7L).factoryId(FACTORY_ID).intermediateBatchNo("WIP-QCS").unit("kg")
                    .producedQuantity(new BigDecimal("8")).consumedQuantity(BigDecimal.ZERO)
                    .availableQuantity(new BigDecimal("8"))
                    .status(SemiFinishedInventory.Status.AVAILABLE)
                    .inspectionStatus(inspection)
                    .inspectionPendingQuantity(pendingQty == null ? null : new BigDecimal(pendingQty))
                    .build();
            when(semiRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(s));
            return s;
        }

        private ProductionSettlementConsumption line(String qty) {
            ProductionSettlementConsumption c = new ProductionSettlementConsumption();
            c.setId(1L);
            c.setSettlementId("S-1");
            c.setSourceType("SEMI_FINISHED");
            c.setSemiFinishedInventoryId(7L);
            c.setQuantity(new BigDecimal(qty));
            c.setUnit("kg");
            return c;
        }

        @Test
        @DisplayName("整行待检 → 拒 (QC_SEMI_PENDING_INSPECTION), 一克不扣, 不写流水")
        void wholePendingRejected() {
            SemiFinishedInventory s = sfi(SemiFinishedInventory.InspectionStatus.PENDING, null);
            assertThatThrownBy(() -> post(line("5")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.SEMI_PENDING));
            assertThat(s.getConsumedQuantity()).isEqualByComparingTo("0");
            verify(semiRepo, never()).save(any());
            verify(semiTxnRepo, never()).save(any());
        }

        @Test
        @DisplayName("部分待检 (8 里 3 待检): 领 5 通过并扣减; 领 6 被本闸拒")
        void partialPendingIsQuantityLevel() {
            SemiFinishedInventory s = sfi(SemiFinishedInventory.InspectionStatus.PENDING, "3");
            assertThatThrownBy(() -> post(line("6")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.SEMI_PENDING));
            post(line("5"));
            assertThat(s.getConsumedQuantity()).isEqualByComparingTo("5");
            assertThat(s.getAvailableQuantity()).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("阴性对照: 同一行不在待检 → 照常扣 5")
        void notPendingIsDeducted() {
            SemiFinishedInventory s = sfi(null, null);
            post(line("5"));
            assertThat(s.getConsumedQuantity()).isEqualByComparingTo("5");
            verify(semiTxnRepo).save(any(SemiFinishedInventoryTransaction.class));
        }
    }

    @Nested
    @DisplayName("高 1: 结单领用在制批次")
    class WipDraw {

        private MaterialBatch mb(String sourceDocType, MaterialBatchStatus status) {
            MaterialBatch b = new MaterialBatch();
            b.setId("MB-W");
            b.setFactoryId(FACTORY_ID);
            b.setBatchNumber("WIP-CLK-W-1");
            b.setMaterialTypeId("RM-1");
            b.setReceiptQuantity(new BigDecimal("30"));
            b.setUsedQuantity(BigDecimal.ZERO);
            b.setReservedQuantity(BigDecimal.ZERO);
            b.setQuantityUnit("kg");
            b.setStatus(status);
            b.setSourceDocType(sourceDocType);
            b.setSourceDocId("901");
            when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-W", FACTORY_ID)).thenReturn(Optional.of(b));
            // 缺口 16: 结单领在制批次时按来源生产批次判归属, 来源必须查得到
            com.cretas.aims.entity.ProductionBatch source = new com.cretas.aims.entity.ProductionBatch();
            source.setId(901L);
            when(productionBatchRepository.findByIdAndFactoryId(901L, FACTORY_ID)).thenReturn(Optional.of(source));
            return b;
        }

        private ProductionSettlementConsumption line() {
            ProductionSettlementConsumption c = new ProductionSettlementConsumption();
            c.setSourceType("RAW_MATERIAL");
            c.setMaterialBatchId("MB-W");
            c.setMaterialTypeId("RM-1");
            c.setQuantity(new BigDecimal("4"));
            c.setUnit("kg");
            return c;
        }

        @Test
        @DisplayName("在制批次待检 → 拒 (QC_WIP_PENDING_INSPECTION), 不扣")
        void pendingWipRejected() {
            MaterialBatch b = mb(QcInspectionGuard.WIP_SOURCE_DOC_TYPE, MaterialBatchStatus.INSPECTING);
            assertThatThrownBy(() -> post(line()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QcInspectionGuard.WIP_PENDING));
            assertThat(b.getUsedQuantity()).isEqualByComparingTo("0");
            verify(materialBatchRepository, never()).save(any());
        }

        @Test
        @DisplayName("阴性对照: 同一在制批次已放行 (AVAILABLE) → 照常扣 4")
        void availableWipIsDeducted() {
            MaterialBatch b = mb(QcInspectionGuard.WIP_SOURCE_DOC_TYPE, MaterialBatchStatus.AVAILABLE);
            assertThatCode(() -> post(line())).doesNotThrowAnyException();
            assertThat(b.getUsedQuantity()).isEqualByComparingTo("4");
        }
    }

    @Nested
    @DisplayName("中高 4: 小结成品核验认待检")
    class InterimPendingFinishedGoods {

        private FinishedGoodsBatch fg(String status) {
            FinishedGoodsBatch fg = new FinishedGoodsBatch();
            fg.setId("FG-1");
            fg.setFactoryId(FACTORY_ID);
            fg.setBatchNumber("FG-QCS-1");
            fg.setProductionPlanId(PLAN_ID);
            fg.setProductTypeId("PT-1");
            fg.setUnit("kg");
            fg.setProducedQuantity(BigDecimal.TEN);
            fg.setStatus(status);
            return fg;
        }

        private void validate(FinishedGoodsBatch fg) {
            ReflectionTestUtils.invokeMethod(service, "validateInterimFinishedGoodsIdentity", plan(), fg);
        }

        @Test
        @DisplayName("没开需检的产品, 同计划 CCP 偏离把成品扣成待检 → 结单核验照样通过 (不 409)")
        void pendingInspectionIsAValidReceipt() {
            assertThatCode(() -> validate(fg(FinishedGoodsBatch.Status.PENDING_INSPECTION)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("阴性对照: 白名单之外的状态 (已冲销 REVERSED) 照旧被拒 —— 证明放行待检的是白名单本身")
        void reversedIsStillRejected() {
            assertThatThrownBy(() -> validate(fg(FinishedGoodsBatch.Status.REVERSED)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不是有效的已入库真值");
        }
    }
}
