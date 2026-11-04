package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementLaborRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.utils.ExcelUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S315-02: {@code POST /settle} 的 confirm 语义 (真实入口 {@link ProductionPlanServiceImpl#settleProduction})。
 *
 * <p>场景取自验收: 腌制出 0.9kg 半成品, 成型投 0.5kg 出 5 袋成品, 剩 0.4kg 半成品。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductionSettlementConfirmContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "705a2c0e-plan";
    private static final String WIP_BATCH_NO = "CLK-W-1";
    private static final Long WIP_BATCH_ID = 501L;

    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository planBatchUsageRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private ProductionPlanMapper planMapper;
    @Mock private ConversionRepository conversionRepository;
    @Mock private SchedulingService schedulingService;
    @Mock private ProductionLineRepository productionLineRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private SalesOrderItemRepository salesOrderItemRepository;
    @Mock private ProductionSettlementRepository settlementRepository;
    @Mock private ProductionSettlementConsumptionRepository consumptionRepository;
    @Mock private ProductionSettlementLaborRepository laborRepository;
    @Mock private ProcessSheetRowRepository rowRepository;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private WorkshopReportReservationService reservationService;
    @Mock private CustomerSuppliedSettlementCoverageGuard coverageGuard;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProductionPlanServiceImpl service;
    private ProductionPlan plan;
    private MaterialBatch wipBatch;

    @BeforeEach
    void setUp() throws Exception {
        service = new ProductionPlanServiceImpl(planRepository, batchRepository, materialBatchRepository,
                materialConsumptionRepository, planBatchUsageRepository, productTypeRepository, planMapper,
                conversionRepository, schedulingService, productionLineRepository, userRepository, excelUtil,
                salesOrderRepository, salesOrderItemRepository);
        ReflectionTestUtils.setField(service, "productionSettlementRepository", settlementRepository);
        ReflectionTestUtils.setField(service, "productionSettlementConsumptionRepository", consumptionRepository);
        ReflectionTestUtils.setField(service, "productionSettlementLaborRepository", laborRepository);
        ReflectionTestUtils.setField(service, "processSheetRowRepository", rowRepository);
        ReflectionTestUtils.setField(service, "workshopReservationService", reservationService);
        ReflectionTestUtils.setField(service, "customerSuppliedSettlementCoverageGuard", coverageGuard);
        ReflectionTestUtils.setField(service, "settlementSemiStockingService",
                new SettlementSemiStockingService(rowRepository, materialBatchRepository, wipInventoryService, mapper));

        plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PLAN-B238C1D9");
        plan.setProductTypeId("FG-1");
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);

        wipBatch = new MaterialBatch();
        wipBatch.setBatchNumber("WIP-" + WIP_BATCH_NO);
        wipBatch.setReceiptQuantity(new BigDecimal("0.9"));
        wipBatch.setUsedQuantity(new BigDecimal("0.5"));

        when(planRepository.findByIdForUpdate(PLAN)).thenReturn(Optional.of(plan));
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan));
        when(planRepository.save(any(ProductionPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(batchRepository.findByFactoryIdAndProductionPlanId(FACTORY, PLAN)).thenReturn(List.of());
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull(
                any(), any(), any())).thenReturn(Optional.empty());
        when(settlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN))
                .thenReturn(Optional.empty());
        when(settlementRepository.save(any(ProductionSettlement.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consumptionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(laborRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(reservationService.releaseForPlan(any(), any())).thenReturn(BigDecimal.ZERO);
        when(coverageGuard.audit(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(conversionRepository.findAll()).thenReturn(Collections.emptyList());
        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                FACTORY, "PRODUCTION_BATCH", String.valueOf(WIP_BATCH_ID))).thenReturn(Optional.of(wipBatch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(sourceRoute());
    }

    private List<ProcessSheetRow> sourceRoute() throws Exception {
        ProcessSheetRowRequest semi = new ProcessSheetRowRequest();
        semi.setClientRowId("semi");
        semi.setProcessCode("cure");
        semi.setProcessOrder(1);
        semi.setProductTypeId("SEMI-1");
        semi.setOutputQuantity(new BigDecimal("0.9"));
        semi.setUnit("kg");

        ProcessSheetRowRequest finished = new ProcessSheetRowRequest();
        finished.setClientRowId("fg");
        finished.setProcessCode("form");
        finished.setProcessOrder(2);
        finished.setProductTypeId("FG-1");
        finished.setFinished(true);
        finished.setOutputQuantity(new BigDecimal("5"));
        finished.setUnit("袋");
        ProcessSheetRowRequest.UpstreamRef feed = new ProcessSheetRowRequest.UpstreamRef();
        feed.setSourceBatchNumber(WIP_BATCH_NO);
        feed.setFeedQuantityKg(new BigDecimal("0.5"));
        finished.setUpstreamSources(List.of(feed));

        return List.of(row(semi, WIP_BATCH_ID, WIP_BATCH_NO), row(finished, 502L, "CLK-B-1"));
    }

    private ProcessSheetRow row(ProcessSheetRowRequest req, Long batchId, String batchNumber) throws Exception {
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY);
        row.setPlanId(PLAN);
        row.setBatchId(batchId);
        row.setBatchNumber(batchNumber);
        row.setProcessOrder(req.getProcessOrder());
        row.setSubmissionStatus(ProcessSheetRow.SUBMISSION_SUBMITTED);
        row.setRowPayload(mapper.writeValueAsString(req));
        return row;
    }

    private ProductionSettlementRequest.ProductionSettlementRequestBuilder request(boolean confirm, String semi) {
        return ProductionSettlementRequest.builder()
                .idempotencyKey("key-1")
                .confirm(confirm)
                .actualFinishedQuantity(new BigDecimal("5"))
                .actualSemiFinishedQuantity(semi == null ? null : new BigDecimal(semi))
                .materialVarianceReason("本计划无外部领料")
                .laborDeferredReason("工时稍后补录");
    }

    private List<String> writesSeen() {
        Set<String> writePrefixes = Set.of("save", "delete", "stamp", "release", "post", "consume",
                "deduct", "reverse", "restore", "publish");
        return Stream.of(planRepository, batchRepository, materialBatchRepository, materialConsumptionRepository,
                        settlementRepository, consumptionRepository, laborRepository, rowRepository,
                        wipInventoryService, reservationService)
                .flatMap(mock -> Mockito.mockingDetails(mock).getInvocations().stream())
                .map(invocation -> invocation.getMethod().getName())
                .filter(name -> writePrefixes.stream().anyMatch(name::startsWith))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Test
    @DisplayName("confirm=false 只预览: 零写入, 计划状态不变, 返回将要结成的数和 settled=false")
    void previewWritesNothing() {
        ProductionSettlementResponse response =
                service.settleProduction(FACTORY, PLAN, request(false, "0.4").build(), 10L);

        // 「没写」要配一个「一定读了」: 读到了报工行, 才说明预览真的走完了判定
        verify(rowRepository, Mockito.atLeastOnce()).findByFactoryIdAndPlanId(FACTORY, PLAN);
        assertThat(writesSeen()).isEmpty();
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
        assertThat(response.getSettled()).isFalse();
        assertThat(response.getSettlementId()).isNull();
        assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.getActualSemiFinishedQuantity()).isEqualByComparingTo("0.4");
        assertThat(response.getActualFinishedQuantity()).isEqualByComparingTo("5");
        assertThat(response.getWarnings()).anyMatch(w -> w.contains("没有写入任何数据"));
    }

    @Test
    @DisplayName("confirm=false 与正式结单同一判定: 超过剩余量在预览就拒绝, 仍零写入")
    void previewRejectsWhatConfirmWouldReject() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, request(false, "0.5").build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_EXCEEDS_LEFTOVER");
        assertThat(writesSeen()).isEmpty();
    }

    @Test
    @DisplayName("confirm=true 按传入的半成品量结单, 不改成 0; 半成品入库, 同量从在制批次扣掉")
    void confirmKeepsTheQuantityAndStocksIt() {
        ProductionSettlementResponse response =
                service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        assertThat(response.getSettled()).isTrue();
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.COMPLETED);
        assertThat(response.getActualSemiFinishedQuantity()).isEqualByComparingTo("0.4");
        verify(wipInventoryService).postClerkOutput(eq(FACTORY), anyString(), eq("SEMI-1"),
                eq(new BigDecimal("0.4")), eq("kg"), isNull(), isNull(), eq(1));
        assertThat(wipBatch.getUsedQuantity()).isEqualByComparingTo("0.9");
    }

    @Test
    @DisplayName("confirm=true 不带半成品量: 不入库, 也不动在制批次")
    void confirmWithoutSemiStocksNothing() {
        service.settleProduction(FACTORY, PLAN, request(true, "0").build(), 10L);

        verify(wipInventoryService, never()).postClerkOutput(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(wipBatch.getUsedQuantity()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("入参不合法就报错: null / 负数 / 超剩余量, 都不兜底成 0, 也不结单")
    void invalidQuantityIsRejectedNotZeroed() {
        for (String semi : new String[] {null, "-0.1"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.settleProduction(FACTORY, PLAN, request(true, semi).build(), 10L));
            assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_QUANTITY_INVALID");
        }
        BusinessException over = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, request(true, "0.41").build(), 10L));
        assertThat(over.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_EXCEEDS_LEFTOVER");

        verify(settlementRepository, never()).save(any());
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("confirm=true 传的成品量与报工对不上: 拒绝, 不悄悄换成报工的数")
    void finishedQuantityConflictIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.settleProduction(
                FACTORY, PLAN, request(true, "0").actualFinishedQuantity(new BigDecimal("999")).build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("PRODUCTION_SETTLEMENT_QUANTITY_CONFLICT");
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("没有剩余半成品却填了量: 拒绝 (App 报工的半成品在报工时已入库, 不能再入一次)")
    void noLeftoverIsRejected() throws Exception {
        ProcessSheetRowRequest finished = new ProcessSheetRowRequest();
        finished.setClientRowId("fg");
        finished.setProcessCode("form");
        finished.setProcessOrder(1);
        finished.setProductTypeId("FG-1");
        finished.setFinished(true);
        finished.setOutputQuantity(new BigDecimal("5"));
        finished.setUnit("袋");
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN))
                .thenReturn(List.of(row(finished, 502L, "CLK-B-1")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_NO_LEFTOVER");
        verify(settlementRepository, never()).save(any());
    }
}
