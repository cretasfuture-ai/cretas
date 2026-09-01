package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷 48 回归：本闸的方向曾经是反的（要求单个批次一次性报完整张 SO 客供清单的全部数量），
 * 见 {@link CustomerSuppliedSettlementCoverageGuard} 类注释。这份测试钉住修好之后的两条判据：
 * 「用的料必须在清单里」+「同一种料跨已结单批次累计不能超过已收数量」，以及 SO 清单里没用到
 * /只用了一部分的料不产生任何提示。
 */
class CustomerSuppliedSettlementCoverageGuardContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "plan-a";
    private static final String ORDER = "order-a";

    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    private final SalesOrderSuppliedMaterialRequirementRepository requirementRepository =
            mock(SalesOrderSuppliedMaterialRequirementRepository.class);
    private final MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
    private final ProductionSettlementConsumptionRepository settlementConsumptionRepository =
            mock(ProductionSettlementConsumptionRepository.class);
    private final RawMaterialTypeRepository rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
    private final CustomerSuppliedSettlementCoverageGuard guard =
            new CustomerSuppliedSettlementCoverageGuard(
                    planRepository, requirementRepository, materialBatchRepository,
                    settlementConsumptionRepository, rawMaterialTypeRepository);

    @BeforeEach
    void noSiblingPlansByDefault() {
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER)).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // audit() — 核对结单弹窗
    // ------------------------------------------------------------------

    @Test
    void prodShapePartialUseOfOneOfTwoCustomerMaterialsStaysClean() {
        // PLAN-1788280683352-34F8CD82 的真实形状: SO 有两种客供料 (1399上脑/50CL),
        // 这一批 BOM 只含 1399上脑, 且只用掉 5900kg 里的 100kg —— 不该有任何提示。
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement("pork-1399", "1399上脑", "5900", "5900", "kg"),
                        requirement("beef-50cl", "50CL", "3000", "3000", "kg")));

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "100")));

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
        guard.requireCleanForOneClick(audited);
    }

    @Test
    void materialNotOnTheOrdersSuppliedListBlocksAndNamesIt() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        when(rawMaterialTypeRepository.findByIdIn(Set.of("borrowed-lamb")))
                .thenReturn(List.of(rawMaterialType("borrowed-lamb", "别家订单的羊肉")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN,
                prefill(line("pork-1399", "100"), line("borrowed-lamb", "5")));

        assertFalse(audited.getAudit().isClean());
        assertEquals(1, audited.getAudit().getIssues().size());
        ProductionSettlementPrefillResponse.Issue issue = audited.getAudit().getIssues().get(0);
        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED, issue.getCode());
        assertEquals(ProductionSettlementPrefillResponse.Severity.BLOCKER, issue.getSeverity());
        assertTrue(issue.getMessage().contains("客供清单之外的料"));
        assertTrue(issue.getMessage().contains("别家订单的羊肉"));
        assertFalse(issue.getMessage().contains("1399上脑"));
        assertThrows(BusinessException.class, () -> guard.requireCleanForOneClick(audited));
    }

    @Test
    void cumulativeConsumptionAcrossSettledSiblingPlansOverReceivedBlocksWithNumbers() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        ProductionPlan sibling = plan("plan-b", MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER)).thenReturn(List.of(sibling, plan(PLAN, MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(settlementConsumptionRepository.findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
                FACTORY, List.of("plan-b")))
                .thenReturn(List.of(settledConsumption("pork-1399", "5850")));

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "100")));

        assertFalse(audited.getAudit().isClean());
        ProductionSettlementPrefillResponse.Issue issue = audited.getAudit().getIssues().get(0);
        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED, issue.getCode());
        assertTrue(issue.getMessage().contains("累计已用 5950"), issue.getMessage());
        assertTrue(issue.getMessage().contains("超过已收 5900"), issue.getMessage());
    }

    @Test
    void unusedAndPartiallyUsedOrderMaterialsProduceNoMessage() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement("pork-1399", "1399上脑", "5900", "5900", "kg"),
                        requirement("box", "成品盒", "1", "1", "盒")));

        // 完全没用 box, 只用了 1399上脑 的一部分 —— 两者都不该产生提示。
        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("pork-1399", "1")));

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
    }

    @Test
    void factorySuppliedPlanIsOutsideThisGuard() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.FACTORY_SUPPLIED)));
        ProductionSettlementPrefillResponse response = prefill();

        assertTrue(guard.audit(FACTORY, PLAN, response).getAudit().isClean());
        verify(requirementRepository, never())
                .findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER);
    }

    @Test
    void customerSuppliedPlanWithNoSuppliedMaterialRequirementsAtAllIsANoOp() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of());

        ProductionSettlementPrefillResponse audited =
                guard.audit(FACTORY, PLAN, prefill(line("anything", "1")));

        assertTrue(audited.getAudit().isClean());
    }

    @Test
    void duplicateAuditDoesNotDuplicateTheBlocker() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("box", "成品盒", "1", "1", "盒")));
        ProductionSettlementPrefillResponse response = prefill(line("box", "2"));

        guard.audit(FACTORY, PLAN, response);
        guard.audit(FACTORY, PLAN, response);

        assertEquals(1, response.getAudit().getIssues().stream()
                .filter(issue -> CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED
                        .equals(issue.getCode()))
                .count());
    }

    // ------------------------------------------------------------------
    // mergeMissingManualRawConsumptions() — 结单写路径, 必须与 audit() 同一份口径
    // ------------------------------------------------------------------

    @Test
    void mergeAddsCustomerBatchWithinRemainingAllowance() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement("raw-a", "原料A", "0.21", "0.21", "kg"),
                        requirement("box", "成品盒", "1", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-box", FACTORY))
                .thenReturn(Optional.of(batch("batch-box", "box", "盒")));
        ProductionSettlementRequest.ConsumptionLine reported = consumptionLine("batch-a", "raw-a", "0.21", "kg");
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(reported))).build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(
                        consumptionLine("batch-a", "spoofed", "0.21", "kg"),
                        consumptionLine("batch-box", "spoofed", "1", "kg")))
                .build();

        guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted);

        assertEquals(2, derived.getRawMaterialConsumptions().size());
        assertEquals("raw-a", derived.getRawMaterialConsumptions().get(0).getMaterialTypeId());
        ProductionSettlementRequest.ConsumptionLine added = derived.getRawMaterialConsumptions().get(1);
        assertEquals("box", added.getMaterialTypeId());
        assertEquals("盒", added.getUnit());
        assertEquals("MB-batch-box", added.getBatchNumber());
    }

    @Test
    void mergeRejectsAnyAttemptToOverrideReportedQuantity() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("raw-a", "原料A", "0.21", "0.21", "kg")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-a", "raw-a", "0.21", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(consumptionLine("batch-a", "raw-a", "0.22", "kg")))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals("CUSTOMER_SUPPLIED_REPORTED_FACT_OVERRIDE", ex.getErrorCode());
        assertEquals(1, derived.getRawMaterialConsumptions().size());
        verify(materialBatchRepository, never()).findByIdAndFactoryId("batch-a", FACTORY);
    }

    @Test
    void mergeRejectsManualBatchNotOnTheListAndOverGapBatch() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("box", "成品盒", "1", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-extra", FACTORY))
                .thenReturn(Optional.of(batch("batch-extra", "not-required", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-box", FACTORY))
                .thenReturn(Optional.of(batch("batch-box", "box", "盒")));

        BusinessException extra = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan,
                        ProductionSettlementRequest.builder().build(),
                        ProductionSettlementRequest.builder().rawMaterialConsumptions(
                                List.of(consumptionLine("batch-extra", "not-required", "1", "盒"))).build()));
        BusinessException over = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan,
                        ProductionSettlementRequest.builder().build(),
                        ProductionSettlementRequest.builder().rawMaterialConsumptions(
                                List.of(consumptionLine("batch-box", "box", "2", "盒"))).build()));

        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED, extra.getErrorCode());
        assertEquals("CUSTOMER_SUPPLIED_MANUAL_QUANTITY_EXCEEDS_GAP", over.getErrorCode());
    }

    @Test
    void mergeFinalEvaluationCatchesForeignMaterialAlreadyInTheAutoDerivedLines() {
        // 「拿别人的料做这单」不止发生在手工补录 —— 逐道报工本身也可能带出一行不在
        // 客供清单里的批次, 最终整批校验必须照样拦, 不能只查 submitted 里新加的那几行。
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-foreign", "borrowed-lamb", "5", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of()).build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_NOT_LISTED, ex.getErrorCode());
    }

    @Test
    void mergeFinalEvaluationCatchesCumulativeOverageAcrossSettledSiblingPlans() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement("pork-1399", "1399上脑", "5900", "5900", "kg")));
        when(planRepository.findByFactoryIdAndSourceOrderId(FACTORY, ORDER))
                .thenReturn(List.of(plan("plan-b", MaterialSupplyMode.CUSTOMER_SUPPLIED)));
        when(settlementConsumptionRepository.findByFactoryIdAndProductionPlanIdInAndDeletedAtIsNull(
                FACTORY, List.of("plan-b")))
                .thenReturn(List.of(settledConsumption("pork-1399", "5850")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        consumptionLine("batch-a", "pork-1399", "100", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of()).build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE_MATERIAL_OVER_RECEIVED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("5950"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ProductionPlan plan(MaterialSupplyMode supplyMode) {
        return plan(PLAN, supplyMode);
    }

    private static ProductionPlan plan(String id, MaterialSupplyMode supplyMode) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(FACTORY);
        plan.setSourceOrderId(ORDER);
        plan.setMaterialSupplyMode(supplyMode);
        plan.setPlannedQuantity(BigDecimal.ONE);
        return plan;
    }

    private static SalesOrderSuppliedMaterialRequirement requirement(String typeId,
                                                                       String name,
                                                                       String expectedQuantity,
                                                                       String receivedQuantity,
                                                                       String unit) {
        SalesOrderSuppliedMaterialRequirement requirement = new SalesOrderSuppliedMaterialRequirement();
        requirement.setFactoryId(FACTORY);
        requirement.setSalesOrderId(ORDER);
        requirement.setMaterialTypeId(typeId);
        requirement.setMaterialName(name);
        requirement.setExpectedQuantity(new BigDecimal(expectedQuantity));
        requirement.setReceivedQuantity(new BigDecimal(receivedQuantity));
        requirement.setUnit(unit);
        requirement.setExpectedArrivalAt(LocalDateTime.now());
        return requirement;
    }

    private static RawMaterialType rawMaterialType(String id, String name) {
        RawMaterialType type = new RawMaterialType();
        type.setId(id);
        type.setFactoryId(FACTORY);
        type.setName(name);
        return type;
    }

    private static MaterialBatch batch(String id, String materialTypeId, String unit) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setMaterialTypeId(materialTypeId);
        batch.setBatchNumber("MB-" + id);
        batch.setQuantityUnit(unit);
        batch.setWarehouseId("WH-RAW");
        return batch;
    }

    private static ProductionSettlementConsumption settledConsumption(String materialTypeId, String quantity) {
        ProductionSettlementConsumption row = new ProductionSettlementConsumption();
        row.setFactoryId(FACTORY);
        row.setProductionPlanId("plan-b");
        row.setSourceType("RAW_MATERIAL");
        row.setMaterialTypeId(materialTypeId);
        row.setQuantity(new BigDecimal(quantity));
        row.setUnit("kg");
        return row;
    }

    private static ProductionSettlementRequest.ConsumptionLine consumptionLine(
            String batchId, String materialTypeId, String quantity, String unit) {
        return ProductionSettlementRequest.ConsumptionLine.builder()
                .materialBatchId(batchId)
                .materialTypeId(materialTypeId)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    /** Prefill 用的报工带入行 —— 只需要 materialTypeId + quantity, 不需要真实批次。 */
    private static ProductionSettlementRequest.ConsumptionLine line(String materialTypeId, String quantity) {
        return ProductionSettlementRequest.ConsumptionLine.builder()
                .materialTypeId(materialTypeId)
                .quantity(new BigDecimal(quantity))
                .unit("kg")
                .build();
    }

    private static ProductionSettlementPrefillResponse prefill(ProductionSettlementRequest.ConsumptionLine... lines) {
        List<ProductionSettlementRequest.ConsumptionLine> list = new ArrayList<>(List.of(lines));
        return ProductionSettlementPrefillResponse.builder()
                .prefill(ProductionSettlementRequest.builder()
                        .rawMaterialConsumptions(list)
                        .build())
                .audit(ProductionSettlementPrefillResponse.Audit.builder()
                        .clean(true)
                        .issues(new ArrayList<>())
                        .build())
                .build();
    }
}
