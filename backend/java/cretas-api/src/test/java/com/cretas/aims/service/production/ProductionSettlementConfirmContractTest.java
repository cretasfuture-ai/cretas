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
import java.util.Map;
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
    @Mock private com.cretas.aims.service.unit.UnitContractService unitContractService;
    @Mock private com.cretas.aims.repository.SemiFinishedInventoryRepository semiRepository;
    @Mock private com.cretas.aims.service.PermissionService permissionService;

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
                new SettlementSemiStockingService(rowRepository, materialBatchRepository,
                        materialConsumptionRepository, wipInventoryService, unitContractService, mapper));
        ReflectionTestUtils.setField(service, "wipInventoryService", wipInventoryService);
        ReflectionTestUtils.setField(service, "semiFinishedInventoryRepository", semiRepository);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        when(unitContractService.storageUnit(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PLAN-B238C1D9");
        plan.setProductTypeId("FG-1");
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);

        wipBatch = new MaterialBatch();
        wipBatch.setId("MB-WIP");
        wipBatch.setBatchNumber("WIP-" + WIP_BATCH_NO);
        wipBatch.setReceiptQuantity(new BigDecimal("0.9"));
        wipBatch.setUsedQuantity(BigDecimal.ZERO);
        wipBatch.setQuantityUnit("kg");
        wipBatch.setUnitPrice(new BigDecimal("20"));

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
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("MB-WIP", FACTORY)).thenReturn(Optional.of(wipBatch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        // 下道投的 0.5 记在在制批次的消耗记录上 (库存单位), 不在逐道行里现算
        when(materialConsumptionRepository.findByFactoryIdAndBatchId(FACTORY, "MB-WIP"))
                .thenAnswer(inv -> new ArrayList<>(consumptions));
        consumptions.add(consumption("0.5"));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(sourceRoute());
    }

    private final List<com.cretas.aims.entity.MaterialConsumption> consumptions = new ArrayList<>();

    private static com.cretas.aims.entity.MaterialConsumption consumption(String quantity) {
        com.cretas.aims.entity.MaterialConsumption consumption = new com.cretas.aims.entity.MaterialConsumption();
        consumption.setQuantity(new BigDecimal(quantity));
        return consumption;
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
    @DisplayName("confirm=false: 半成品量超过剩余量在预览就拒绝, 仍零写入 (预览不校验库存行, 那部分要到正式结单)")
    void previewRejectsOverLeftoverBeforeAnyWrite() {
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
                eq(new BigDecimal("0.4")), eq("kg"),
                org.mockito.ArgumentMatchers.argThat(cost -> cost.compareTo(new BigDecimal("20")) == 0),
                anyList(), eq(1));
        // 下道投的 0.5 + 转走的 0.4 = 收到的 0.9, 在制批次见底
        assertThat(wipBatch.getUsedQuantity()).isEqualByComparingTo("0.9");
        assertThat(wipBatch.getCurrentQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("confirm=true 明确传 0 (用户确认没有半成品要入库): 不入库, 也不动在制批次")
    void confirmWithExplicitZeroStocksNothing() {
        service.settleProduction(FACTORY, PLAN, request(true, "0").build(), 10L);

        verify(wipInventoryService, never()).postClerkOutput(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(wipBatch.getUsedQuantity()).isEqualByComparingTo("0");
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

    private ArgumentCapturingSettlement capture() {
        return new ArgumentCapturingSettlement();
    }

    private final class ArgumentCapturingSettlement {
        ProductionSettlement saved() {
            org.mockito.ArgumentCaptor<ProductionSettlement> captor =
                    org.mockito.ArgumentCaptor.forClass(ProductionSettlement.class);
            verify(settlementRepository, Mockito.atLeastOnce()).save(captor.capture());
            return captor.getValue();
        }
    }

    @Test
    @DisplayName("入库明细落在结单上, 行上记着来源批: 撤回和追溯都靠它")
    void stockingDetailAndSourceBatchAreRecorded() {
        service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        org.mockito.ArgumentCaptor<List<Map<String, Object>>> refs = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(wipInventoryService).postClerkOutput(any(), any(), any(), any(), any(), any(), refs.capture(), any());
        assertThat(refs.getValue().get(0)).containsEntry("productionBatchId", WIP_BATCH_ID);
        var detail = SettlementSemiStockingService.parseDetail(capture().saved().getSemiStockingDetail());
        assertThat(detail).hasSize(1);
        assertThat(detail.get(0).materialBatchId()).isEqualTo("MB-WIP");
        assertThat(detail.get(0).productionBatchId()).isEqualTo(WIP_BATCH_ID);
        assertThat(detail.get(0).storageQuantity()).isEqualByComparingTo("0.4");
    }

    @Test
    @DisplayName("别的计划从这批领的料也算: 剩余量看在制批次的全部消耗")
    void otherPlansConsumptionCounts() {
        consumptions.add(consumption("0.3"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_EXCEEDS_LEFTOVER");
    }

    @Test
    @DisplayName("单位不同走换算: 在制批次按 g 存, 半成品按 kg 算")
    void leftoverIsConvertedFromStorageUnit() {
        wipBatch.setReceiptQuantity(new BigDecimal("900"));
        wipBatch.setQuantityUnit("g");
        consumptions.clear();
        consumptions.add(consumption("500"));

        service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        assertThat(wipBatch.getUsedQuantity()).isEqualByComparingTo("900");
        verify(wipInventoryService).postClerkOutput(any(), any(), any(),
                org.mockito.ArgumentMatchers.argThat(q -> q.compareTo(new BigDecimal("0.4")) == 0), eq("kg"),
                any(), any(), any());
    }

    @Test
    @DisplayName("上限也按换算后算: 剩 400g, 填 0.5kg 要拒绝")
    void leftoverLimitIsConvertedToo() {
        wipBatch.setReceiptQuantity(new BigDecimal("900"));
        wipBatch.setQuantityUnit("g");
        consumptions.clear();
        consumptions.add(consumption("500"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, request(false, "0.5").build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_EXCEEDS_LEFTOVER");
    }

    @Test
    @DisplayName("单位没有换算关系: 拒绝, 不当成相等")
    void unconvertibleUnitIsRejected() {
        wipBatch.setQuantityUnit("盒");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_SEMI_UNIT_MISMATCH");
    }

    @Test
    @DisplayName("只出半成品的非 Workflow 计划: 没有成品也能结单, 半成品入库")
    void semiOnlyPlanCanSettle() throws Exception {
        ProcessSheetRowRequest semi = new ProcessSheetRowRequest();
        semi.setClientRowId("semi");
        semi.setProcessCode("cure");
        semi.setProcessOrder(1);
        semi.setProductTypeId("SEMI-1");
        semi.setOutputQuantity(new BigDecimal("0.9"));
        semi.setUnit("kg");
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN))
                .thenReturn(List.of(row(semi, WIP_BATCH_ID, WIP_BATCH_NO)));

        ProductionSettlementResponse response = service.settleProduction(FACTORY, PLAN,
                request(true, "0.4").actualFinishedQuantity(BigDecimal.ZERO).build(), 10L);

        assertThat(response.getActualSemiFinishedQuantity()).isEqualByComparingTo("0.4");
        assertThat(response.getActualFinishedQuantity()).as("半成品行不算成品").isEqualByComparingTo("0");
        assertThat(plan.getStatus()).isEqualTo(ProductionPlanStatus.COMPLETED);
    }

    @Test
    @DisplayName("半成品全被下道投掉、下道还没产出: 没有终端行也能按半成品量结单")
    void semiOnlyPlanWithoutTerminalRowCanSettle() throws Exception {
        ProcessSheetRowRequest semi = new ProcessSheetRowRequest();
        semi.setClientRowId("semi");
        semi.setProcessCode("cure");
        semi.setProcessOrder(1);
        semi.setProductTypeId("SEMI-1");
        semi.setOutputQuantity(new BigDecimal("0.9"));
        semi.setUnit("kg");
        ProcessSheetRowRequest next = new ProcessSheetRowRequest();
        next.setClientRowId("next");
        next.setProcessCode("form");
        next.setProcessOrder(2);
        next.setProductTypeId("SEMI-2");
        ProcessSheetRowRequest.UpstreamRef feed = new ProcessSheetRowRequest.UpstreamRef();
        feed.setSourceBatchNumber(WIP_BATCH_NO);
        feed.setFeedQuantityKg(new BigDecimal("0.5"));
        next.setUpstreamSources(List.of(feed));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN))
                .thenReturn(List.of(row(semi, WIP_BATCH_ID, WIP_BATCH_NO), row(next, 502L, "CLK-W-2")));

        ProductionSettlementResponse response = service.settleProduction(FACTORY, PLAN,
                request(true, "0.4").actualFinishedQuantity(null).build(), 10L);

        assertThat(response.getActualSemiFinishedQuantity()).isEqualByComparingTo("0.4");
    }

    @Test
    @DisplayName("有成品行的计划不受影响: 传了半成品量, 成品量仍按报工")
    void planWithFinishedRowKeepsItsFinishedOutput() {
        ProductionSettlementResponse response =
                service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        assertThat(response.getActualFinishedQuantity()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("Workflow 计划缺成品产出: 半成品量不能把它放过去")
    void workflowPlanStillNeedsReportedFinishedOutput() throws Exception {
        plan.setWorkflowSelectionMode(com.cretas.aims.entity.ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        plan.setSelectedWorkflowId(44L);
        plan.setSkipProcessReporting(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.settleProduction(FACTORY, PLAN,
                request(true, "0.4").actualFinishedQuantity(null).build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("PRODUCTION_SETTLEMENT_BLOCKED");
        verify(settlementRepository, never()).save(any());
    }

    private ProductionSettlementRequest.ProductionSettlementRequestBuilder manual() {
        return request(true, "0").manualOverride(true).manualOverrideReason("报工数据缺失")
                .laborDeferredReason("工时稍后补录");
    }

    private void userHasProductionWrite(boolean allowed) {
        com.cretas.aims.entity.User user = new com.cretas.aims.entity.User();
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(user, "production:read_write")).thenReturn(allowed);
    }

    @Test
    @DisplayName("人工补录: 标记、原因、操作人都落在结单上")
    void manualOverrideIsAudited() {
        userHasProductionWrite(true);

        ProductionSettlementResponse response = service.settleProduction(FACTORY, PLAN, manual().build(), 10L);

        ProductionSettlement saved = capture().saved();
        assertThat(saved.isManualOverride()).isTrue();
        assertThat(saved.getManualOverrideReason()).isEqualTo("报工数据缺失");
        assertThat(saved.getSettledBy()).isEqualTo(10L);
        assertThat(response.getManualOverride()).isTrue();
    }

    @Test
    @DisplayName("普通结单不留人工补录标记")
    void ordinarySettleIsNotMarkedManual() {
        service.settleProduction(FACTORY, PLAN, request(true, "0").build(), 10L);

        assertThat(capture().saved().isManualOverride()).isFalse();
    }

    @Test
    @DisplayName("人工补录没写原因: 拒绝, 不结单")
    void manualOverrideNeedsReason() {
        userHasProductionWrite(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.settleProduction(
                FACTORY, PLAN, manual().manualOverrideReason("  ").build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_REASON_REQUIRED");
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("只有排程写权限: 人工补录 403, 不结单")
    void schedulingOnlyCannotManualOverride() {
        userHasProductionWrite(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, manual().build(), 10L));

        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_OVERRIDE_FORBIDDEN");
        verify(settlementRepository, never()).save(any());
    }

    private void reportedSemiFeed(String quantity) throws Exception {
        ProcessSheetRowRequest fed = new ProcessSheetRowRequest();
        fed.setClientRowId("fed");
        fed.setProcessCode("form");
        fed.setProcessOrder(2);
        fed.setProductTypeId("FG-1");
        fed.setFinished(true);
        fed.setOutputQuantity(new BigDecimal("5"));
        fed.setUnit("袋");
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber("CLK-SEMI-1");
        ref.setSemiFinished(true);
        ref.setFeedQuantityKg(new BigDecimal(quantity));
        fed.setUpstreamSources(List.of(ref));
        when(rowRepository.findByFactoryIdAndPlanId(FACTORY, PLAN)).thenReturn(List.of(row(fed, 502L, "CLK-B-1")));
        var sourceRef = Mockito.mock(com.cretas.aims.repository.SemiFinishedInventoryRepository.SourceRef.class);
        when(sourceRef.getFactoryId()).thenReturn(FACTORY);
        when(sourceRef.getIntermediateBatchNo()).thenReturn("CLK-SEMI-1");
        when(semiRepository.findSourceRefById(7L)).thenReturn(Optional.of(sourceRef));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(eq(FACTORY), eq("CLK-SEMI-1"), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        userHasProductionWrite(true);
    }

    private ProductionSettlementRequest manualSemi(String quantity) {
        return manual().semiFinishedConsumptions(List.of(ProductionSettlementRequest.ConsumptionLine.builder()
                .semiFinishedInventoryId(7L).quantity(new BigDecimal(quantity)).unit("kg").build())).build();
    }

    @Test
    @DisplayName("手工半成品领用少报: 报工投 100 手工填 1 → 拒绝, 不让 99 变幻库存; 预览同样拒绝")
    void manualSemiBelowReportedFeedIsRejected() throws Exception {
        reportedSemiFeed("100");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, manualSemi("1"), 10L));
        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_MISMATCH");
        verify(settlementRepository, never()).save(any());

        ProductionSettlementRequest preview = manualSemi("1");
        preview.setConfirm(false);
        BusinessException previewEx = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, preview, 10L));
        assertThat(previewEx.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_MISMATCH");
    }

    @Test
    @DisplayName("手工半成品领用多报同样拒绝; 量对得上才过这道闸")
    void manualSemiMustMatchExactly() throws Exception {
        reportedSemiFeed("100");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.settleProduction(FACTORY, PLAN, manualSemi("101"), 10L));
        assertThat(ex.getErrorCode()).isEqualTo("SETTLEMENT_MANUAL_SEMI_MISMATCH");

        ProductionSettlementRequest matching = manualSemi("100");
        matching.setConfirm(false);
        assertThat(service.settleProduction(FACTORY, PLAN, matching, 10L).getSettled()).isFalse();
    }

    @Test
    @DisplayName("成本随货走: 0.4kg × 单价 20 = 8 记在入库明细上, 半成品单价 20")
    void costFollowsTheStockedSemi() {
        service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        var detail = SettlementSemiStockingService.parseDetail(capture().saved().getSemiStockingDetail());
        assertThat(detail.get(0).transferredCost()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("单位不同也按库存单位算成本: 400g × 0.02/g = 8, 半成品单价 20/kg")
    void costIsComputedInStorageUnit() {
        wipBatch.setReceiptQuantity(new BigDecimal("900"));
        wipBatch.setQuantityUnit("g");
        wipBatch.setUnitPrice(new BigDecimal("0.02"));
        consumptions.clear();
        consumptions.add(consumption("500"));

        service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        var detail = SettlementSemiStockingService.parseDetail(capture().saved().getSemiStockingDetail());
        assertThat(detail.get(0).transferredCost()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("单价未知: 成本保持未知, 不编 0 —— 入库单价 null, 明细里划转成本也是 null")
    void unknownCostStaysUnknown() {
        wipBatch.setUnitPrice(null);

        service.settleProduction(FACTORY, PLAN, request(true, "0.4").build(), 10L);

        verify(wipInventoryService).postClerkOutput(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.isNull(), any(), any());
        var detail = SettlementSemiStockingService.parseDetail(capture().saved().getSemiStockingDetail());
        assertThat(detail.get(0).transferredCost()).isNull();
    }

    @Test
    @DisplayName("分摊产出成本之前, 入库明细已经落到结单上")
    void stockingHappensBeforeOutputCostAllocation() {
        String source;
        try {
            source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(source.indexOf("settlementSemiStockingService.stock(factoryId, plan, semiStocking"))
                .isGreaterThan(0)
                .isLessThan(source.indexOf("persistWorkflowSettlementOutputs(factoryId, plan, settlement, effectiveRequest);"));
    }
}
