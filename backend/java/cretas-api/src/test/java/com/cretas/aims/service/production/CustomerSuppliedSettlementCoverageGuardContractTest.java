package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementPrefillResponse;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerSuppliedSettlementCoverageGuardContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "plan-a";
    private static final String ORDER = "order-a";

    private final ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class);
    private final SalesOrderSuppliedMaterialRequirementRepository requirementRepository =
            mock(SalesOrderSuppliedMaterialRequirementRepository.class);
    private final SalesOrderItemRepository salesOrderItemRepository = mock(SalesOrderItemRepository.class);
    private final MaterialBatchRepository materialBatchRepository = mock(MaterialBatchRepository.class);
    private final CustomerSuppliedSettlementCoverageGuard guard =
            new CustomerSuppliedSettlementCoverageGuard(
                    planRepository, requirementRepository, salesOrderItemRepository, materialBatchRepository);

    @BeforeEach
    void setUpOrderItem() {
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId(ORDER, FACTORY))
                .thenReturn(List.of(orderItem(835L, "1")));
    }

    @Test
    void customerSuppliedExactOrderLineMissingTypesFailClosedAndNameTheMissingMaterial() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement(835L, "raw-a", "原料A", "0.21", "kg"),
                        requirement(835L, "box", "成品盒", "1", "盒"),
                        requirement(836L, "other-line", "其他订单行物料", "9", "kg")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill("raw-a"));

        assertFalse(audited.getAudit().isClean());
        assertEquals(1, audited.getAudit().getIssues().size());
        ProductionSettlementPrefillResponse.Issue issue = audited.getAudit().getIssues().get(0);
        assertEquals(CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE, issue.getCode());
        assertEquals(ProductionSettlementPrefillResponse.Severity.BLOCKER, issue.getSeverity());
        assertTrue(issue.getMessage().contains("客供要求共 2 种"));
        assertTrue(issue.getMessage().contains("逐道报工仅带入 1 种"));
        assertTrue(issue.getMessage().contains("成品盒 要求 1盒，已报 0盒"));
        assertFalse(issue.getMessage().contains("其他订单行物料"));
        assertThrows(BusinessException.class, () -> guard.requireCleanForOneClick(audited));
    }

    @Test
    void completeExactOrderLineStaysClean() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement(835L, "raw-a", "原料A", "0.21", "kg"),
                        requirement(835L, "box", "成品盒", "1", "盒")));

        ProductionSettlementPrefillResponse audited = guard.audit(
                FACTORY, PLAN, prefill("raw-a", "box"));

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
        guard.requireCleanForOneClick(audited);
    }

    @Test
    void reportedTypeWithInsufficientQuantityStillFailsClosed() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(835L, "box", "成品盒", "2", "盒")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill("box"));

        assertFalse(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().get(0).getMessage()
                .contains("成品盒 要求 2盒，已报 1盒"));
    }

    @Test
    void splitProductionPlanUsesOnlyItsShareOfTheOrderLineRequirement() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835");
        plan.setSourceDisplayQuantity(new BigDecimal("1"));
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY)).thenReturn(Optional.of(plan));
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId(ORDER, FACTORY))
                .thenReturn(List.of(orderItem(835L, "2")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(835L, "box", "成品盒", "2", "盒")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill("box"));

        assertTrue(audited.getAudit().isClean());
    }

    @Test
    void singleLineOrderSafelyOwnsLegacyNullLinkedRequirements() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(null, "box", "成品盒", "1", "盒")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill());

        assertFalse(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().get(0).getMessage()
                .contains("成品盒 要求 1盒，已报 0盒"));
    }

    @Test
    void multiLineOrderDoesNotGuessOwnershipOfNullLinkedRequirements() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835")));
        when(salesOrderItemRepository.findBySalesOrderIdAndSalesOrderFactoryId(ORDER, FACTORY))
                .thenReturn(List.of(orderItem(835L, "1"), orderItem(836L, "1")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(null, "box", "成品盒", "1", "盒")));

        ProductionSettlementPrefillResponse audited = guard.audit(FACTORY, PLAN, prefill());

        assertTrue(audited.getAudit().isClean());
        assertTrue(audited.getAudit().getIssues().isEmpty());
    }

    @Test
    void factorySuppliedPlanIsOutsideThisGuard() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.FACTORY_SUPPLIED, "835")));
        ProductionSettlementPrefillResponse response = prefill();

        assertTrue(guard.audit(FACTORY, PLAN, response).getAudit().isClean());
        verify(requirementRepository, never())
                .findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER);
    }

    @Test
    void duplicateAuditDoesNotDuplicateTheBlocker() {
        when(planRepository.findByIdAndFactoryId(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835")));
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(835L, "box", "成品盒", "1", "盒")));
        ProductionSettlementPrefillResponse response = prefill();

        guard.audit(FACTORY, PLAN, response);
        guard.audit(FACTORY, PLAN, response);

        assertEquals(1, response.getAudit().getIssues().stream()
                .filter(issue -> CustomerSuppliedSettlementCoverageGuard.ISSUE_CODE.equals(issue.getCode()))
                .count());
    }

    @Test
    void mergeKeepsReportedFactsAndAddsOnlyTheExactMissingCustomerBatch() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835");
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(
                        requirement(835L, "raw-a", "原料A", "0.21", "kg"),
                        requirement(835L, "box", "成品盒", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-box", FACTORY))
                .thenReturn(Optional.of(batch("batch-box", "box", "盒")));
        ProductionSettlementRequest.ConsumptionLine reported = line("batch-a", "raw-a", "0.21", "kg");
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(reported))).build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(
                        line("batch-a", "spoofed", "0.21", "kg"),
                        line("batch-box", "spoofed", "1", "kg")))
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
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835");
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(835L, "raw-a", "原料A", "0.21", "kg")));
        ProductionSettlementRequest derived = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(new ArrayList<>(List.of(
                        line("batch-a", "raw-a", "0.21", "kg"))))
                .build();
        ProductionSettlementRequest submitted = ProductionSettlementRequest.builder()
                .rawMaterialConsumptions(List.of(line("batch-a", "raw-a", "0.22", "kg")))
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan, derived, submitted));

        assertEquals("CUSTOMER_SUPPLIED_REPORTED_FACT_OVERRIDE", ex.getErrorCode());
        assertEquals(1, derived.getRawMaterialConsumptions().size());
        verify(materialBatchRepository, never()).findByIdAndFactoryId("batch-a", FACTORY);
    }

    @Test
    void mergeRejectsExtraOrOverGapManualBatch() {
        ProductionPlan plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "835");
        when(requirementRepository.findBySalesOrderIdOrderByExpectedArrivalAtAscIdAsc(ORDER))
                .thenReturn(List.of(requirement(835L, "box", "成品盒", "1", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-extra", FACTORY))
                .thenReturn(Optional.of(batch("batch-extra", "not-required", "盒")));
        when(materialBatchRepository.findByIdAndFactoryId("batch-box", FACTORY))
                .thenReturn(Optional.of(batch("batch-box", "box", "盒")));

        BusinessException extra = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan,
                        ProductionSettlementRequest.builder().build(),
                        ProductionSettlementRequest.builder().rawMaterialConsumptions(
                                List.of(line("batch-extra", "not-required", "1", "盒"))).build()));
        BusinessException over = assertThrows(BusinessException.class,
                () -> guard.mergeMissingManualRawConsumptions(FACTORY, plan,
                        ProductionSettlementRequest.builder().build(),
                        ProductionSettlementRequest.builder().rawMaterialConsumptions(
                                List.of(line("batch-box", "box", "2", "盒"))).build()));

        assertEquals("CUSTOMER_SUPPLIED_MANUAL_LINE_NOT_REQUIRED", extra.getErrorCode());
        assertEquals("CUSTOMER_SUPPLIED_MANUAL_QUANTITY_EXCEEDS_GAP", over.getErrorCode());
    }

    private static ProductionPlan plan(MaterialSupplyMode supplyMode, String orderItemId) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN);
        plan.setFactoryId(FACTORY);
        plan.setSourceOrderId(ORDER);
        plan.setSourceOrderItemId(orderItemId);
        plan.setMaterialSupplyMode(supplyMode);
        plan.setSourceDisplayQuantity(BigDecimal.ONE);
        plan.setPlannedQuantity(BigDecimal.ONE);
        return plan;
    }

    private static SalesOrderItem orderItem(Long id, String quantity) {
        SalesOrderItem item = new SalesOrderItem();
        item.setId(id);
        item.setSalesOrderId(ORDER);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    private static SalesOrderSuppliedMaterialRequirement requirement(Long itemId,
                                                                      String typeId,
                                                                      String name,
                                                                      String quantity,
                                                                      String unit) {
        SalesOrderSuppliedMaterialRequirement requirement =
                new SalesOrderSuppliedMaterialRequirement();
        requirement.setFactoryId(FACTORY);
        requirement.setSalesOrderId(ORDER);
        requirement.setSalesOrderItemId(itemId);
        requirement.setMaterialTypeId(typeId);
        requirement.setMaterialName(name);
        requirement.setExpectedQuantity(new BigDecimal(quantity));
        requirement.setReceivedQuantity(new BigDecimal(quantity));
        requirement.setUnit(unit);
        requirement.setExpectedArrivalAt(LocalDateTime.now());
        return requirement;
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

    private static ProductionSettlementRequest.ConsumptionLine line(
            String batchId, String materialTypeId, String quantity, String unit) {
        return ProductionSettlementRequest.ConsumptionLine.builder()
                .materialBatchId(batchId)
                .materialTypeId(materialTypeId)
                .quantity(new BigDecimal(quantity))
                .unit(unit)
                .build();
    }

    private static ProductionSettlementPrefillResponse prefill(String... reportedTypes) {
        List<ProductionSettlementRequest.ConsumptionLine> lines = new ArrayList<>();
        for (String type : reportedTypes) {
            lines.add(ProductionSettlementRequest.ConsumptionLine.builder()
                    .materialTypeId(type)
                    .quantity(BigDecimal.ONE)
                    .unit("box".equals(type) ? "盒" : "kg")
                    .build());
        }
        return ProductionSettlementPrefillResponse.builder()
                .prefill(ProductionSettlementRequest.builder()
                        .rawMaterialConsumptions(lines)
                        .build())
                .audit(ProductionSettlementPrefillResponse.Audit.builder()
                        .clean(true)
                        .issues(new ArrayList<>())
                        .build())
                .build();
    }
}
