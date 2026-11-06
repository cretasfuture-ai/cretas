package com.cretas.aims.service.supplyimpact;

import com.cretas.aims.dto.supplyimpact.SupplyImpactDTO;
import com.cretas.aims.dto.supplyimpact.SupplyImpactBoxExposure;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 断供 / 质量 / 涨价三种事件的回归。阳性: 用了该原料的计划/订单出现; 阴性: 不相关产品不出现。
 */
class SupplyImpactContractTest {

    private static final String F = "F-T";
    private final SupplierRepository supplierRepo = mock(SupplierRepository.class);
    private final RawMaterialTypeRepository materialRepo = mock(RawMaterialTypeRepository.class);
    private final SupplierMaterialRepository supplierMaterialRepo = mock(SupplierMaterialRepository.class);
    private final MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
    private final BomRecipeItemRepository itemRepo = mock(BomRecipeItemRepository.class);
    private final BomItemSubstituteRepository substituteRepo = mock(BomItemSubstituteRepository.class);
    private final ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
    private final PlanStandardCostLineRepository lineRepo = mock(PlanStandardCostLineRepository.class);
    private final SalesOrderRepository orderRepo = mock(SalesOrderRepository.class);
    private final SalesOrderItemRepository orderItemRepo = mock(SalesOrderItemRepository.class);
    private final PurchaseOrderRepository poRepo = mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository poItemRepo = mock(PurchaseOrderItemRepository.class);
    private final LineageGraphAssembler lineage = mock(LineageGraphAssembler.class);
    private final UnitConversionService units = mock(UnitConversionService.class);
    private final SupplyImpactBoxExposureService boxExposure = mock(SupplyImpactBoxExposureService.class);
    private SupplyImpactService service;

    @BeforeEach
    void setUp() {
        service = new SupplyImpactService(supplierRepo, materialRepo, supplierMaterialRepo, batchRepo, itemRepo,
                substituteRepo, planRepo, lineRepo, orderRepo, orderItemRepo, poRepo, poItemRepo, lineage, units,
                boxExposure);
        Supplier s = new Supplier();
        s.setId("S1");
        s.setName("供应商甲");
        when(supplierRepo.findByIdAndFactoryId("S1", F)).thenReturn(Optional.of(s));
        RawMaterialType m = new RawMaterialType();
        m.setId("M1");
        m.setName("牛肉");
        m.setUnit("kg");
        m.setUnitPrice(new BigDecimal("10"));
        when(materialRepo.findByIdAndFactoryId("M1", F)).thenReturn(Optional.of(m));
        SupplierMaterial sm = SupplierMaterial.builder().supplierId("S1").materialTypeId("M1").active(true).build();
        when(supplierMaterialRepo.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc(F, "S1"))
                .thenReturn(List.of(sm));
        when(supplierMaterialRepo.findByFactoryIdAndMaterialTypeIdOrderByActiveDescPreferredDescCreatedAtDesc(F, "M1"))
                .thenReturn(List.of(sm));
        when(batchRepo.findByFactoryIdAndSupplierId(F, "S1")).thenReturn(List.of());
        when(batchRepo.findByFactoryIdAndMaterialTypeId(F, "M1")).thenReturn(List.of());
        when(poRepo.findByFactoryIdAndStatusIn(eq(F), any())).thenReturn(List.of());
        when(units.toKg(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(boxExposure.evaluate(any(), any(), any(), anyBoolean()))
                .thenReturn(SupplyImpactBoxExposure.builder().gaps(List.of()).build());

        BomRecipe r1 = new BomRecipe();
        r1.setId("R1");
        r1.setProductTypeId("P1");
        r1.setProductName("牛肉干");
        r1.setVersion(1);
        BomRecipeItem it = new BomRecipeItem();
        it.setId(1L);
        it.setRecipeId("R1");
        it.setRecipe(r1);
        it.setMaterialTypeId("M1");
        it.setMaterialName("牛肉");
        when(itemRepo.findCurrentByMaterial(F, "M1")).thenReturn(List.of(it));
        when(itemRepo.findByRecipeIdOrderBySortOrderAsc("R1")).thenReturn(List.of(it));
        when(substituteRepo.findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(any(), any(), any(), any()))
                .thenReturn(List.of());

        // P1 的计划用了牛肉 (带快照), P2 的计划没用
        ProductionPlan p1 = plan("PL1", "P1", "SO1");
        ProductionPlan p2 = plan("PL2", "P2", "SO2");
        when(planRepo.findByFactoryIdAndStatusIn(eq(F), any())).thenReturn(List.of(p1, p2));
        PlanStandardCostLine line = PlanStandardCostLine.builder().factoryId(F).planId("PL1").lineType("MATERIAL")
                .materialTypeId("M1").materialName("牛肉").standardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT)
                .standardRate(new BigDecimal("0.5")).standardUnit("kg").priceUnit("kg")
                .unitPrice(new BigDecimal("9")).build();
        when(lineRepo.findByFactoryIdAndPlanIdOrderByIdAsc(F, "PL1")).thenReturn(List.of(line));
        when(lineRepo.findByFactoryIdAndPlanIdOrderByIdAsc(F, "PL2")).thenReturn(List.of());
        when(itemRepo.findByRecipeIdsWithRecipe(any(), any())).thenReturn(List.of());

        SalesOrder so1 = order("SO1", "ORD-1", "客户A");
        SalesOrder so2 = order("SO2", "ORD-2", "客户B");
        when(orderRepo.findByIdAndFactoryId("SO1", F)).thenReturn(Optional.of(so1));
        when(orderRepo.findByIdAndFactoryId("SO2", F)).thenReturn(Optional.of(so2));
        when(orderRepo.findByFactoryIdAndStatusInOrderByCreatedAtDesc(eq(F), any(), any())).thenReturn(List.of(so1, so2));
        when(orderItemRepo.findBySalesOrderId("SO1")).thenReturn(List.of(orderItem("P1", "牛肉干")));
        when(orderItemRepo.findBySalesOrderId("SO2")).thenReturn(List.of(orderItem("P2", "鸡肉干")));
    }

    @Test
    void disruption_listsOnlyPlansOrdersAndRecipesThatUseTheSupplierMaterial() {
        MaterialBatch b = new MaterialBatch();
        b.setMaterialTypeId("M1");
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        when(batchRepo.findByFactoryIdAndMaterialTypeId(F, "M1")).thenReturn(List.of(b));

        SupplyImpactDTO.DisruptionView v = service.disruption(F, "S1", null, 14);

        assertThat(v.getRecipes()).extracting(SupplyImpactDTO.RecipeDependency::getProductTypeId).containsExactly("P1");
        assertThat(v.getPlans()).extracting(SupplyImpactDTO.PlanImpact::getPlanNumber).containsExactly("PL1");
        assertThat(v.getPlans().get(0).getRequiredQuantity()).isEqualByComparingTo("50"); // 100 单位 × 0.5
        assertThat(v.getOrders()).extracting(SupplyImpactDTO.OrderImpact::getOrderNumber).containsExactly("ORD-1");
        assertThat(v.getStock().get(0).getOnHand()).isEqualByComparingTo("100");
        assertThat(v.getStock().get(0).getCoverDays()).isEqualByComparingTo("28.0"); // 100 / 50 × 14
        // 缺口如实标出: 没有其它供应商
        assertThat(v.getGaps()).extracting(SupplyImpactDTO.Gap::getCode).contains("NO_ALTERNATIVE_SUPPLIER");
        assertThat(v.getRecipes().get(0).getHasAlternative()).isFalse();
    }

    @Test
    void quality_collectsDownstreamCustomersFromLineage_andOnlyRelatedUnshippedOrders() {
        MaterialBatch b = new MaterialBatch();
        b.setId("B1");
        b.setBatchNumber("MB-1");
        b.setMaterialTypeId("M1");
        b.setSupplierId("S1");
        b.setReceiptDate(LocalDate.of(2026, 10, 10));
        b.setReceiptQuantity(new BigDecimal("100"));
        b.setUsedQuantity(new BigDecimal("40"));
        when(batchRepo.findByFactoryIdAndSupplierId(F, "S1")).thenReturn(List.of(b));
        TraceabilityDTO.LineageNode ship = TraceabilityDTO.LineageNode.builder()
                .nodeType(LineageEdgeRecorder.NODE_SHIPMENT_RECORD).nodeId("D1").batchNumber("DLV-1")
                .name("客户A").quantity(5.0).unit("箱").build();
        TraceabilityDTO.LineageNode fin = TraceabilityDTO.LineageNode.builder()
                .nodeType(LineageEdgeRecorder.NODE_FINISHED_BATCH).nodeId("FB1").batchNumber("FB-1").name("牛肉干").build();
        when(lineage.assembleImpact(eq(F), eq(LineageEdgeRecorder.NODE_MATERIAL_BATCH), eq("B1"), anyInt()))
                .thenReturn(TraceabilityDTO.ImpactResponse.builder()
                        .productionBatches(List.of()).finishedBatches(List.of(fin)).shipments(List.of(ship)).build());

        SupplyImpactDTO.QualityView v = service.quality(F, "S1", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(v.getSourceBatches()).hasSize(1);
        assertThat(v.getSourceBatches().get(0).getRemainingQuantity()).isEqualByComparingTo("60");
        assertThat(v.getCustomers()).extracting(SupplyImpactDTO.CustomerExposure::getCustomerName).containsExactly("客户A");
        assertThat(v.getFinishedBatches()).hasSize(1);
        assertThat(v.getUnshippedOrders()).extracting(SupplyImpactDTO.OrderImpact::getOrderNumber).containsExactly("ORD-1");
        // 范围外的批次被排除
        assertThat(service.quality(F, "S1", null, LocalDate.of(2026, 11, 1), null).getSourceBatches()).isEmpty();
    }

    @Test
    void price_usesSnapshotQuantityAndReportsOnlyAffectedProductsAndOrders() {
        SupplyImpactDTO.PriceView v = service.price(F, "M1", new BigDecimal("10"));

        assertThat(v.getProducts()).hasSize(1);
        SupplyImpactDTO.ProductCostChange c = v.getProducts().get(0);
        assertThat(c.getPlanNumber()).isEqualTo("PL1");
        // 标准用量 100 × 0.5 = 50kg, 档案单价 10, 涨 10% ⇒ 整张计划 +50 元, 每计划单位 +0.5
        assertThat(c.getPlanCostDelta()).isEqualByComparingTo("50.00");
        assertThat(c.getUnitCostDelta()).isEqualByComparingTo("0.5");
        assertThat(v.getOrders()).extracting(SupplyImpactDTO.OrderImpact::getOrderNumber).containsExactly("ORD-1");
        assertThat(v.getRecipes()).extracting(SupplyImpactDTO.RecipeDependency::getProductTypeId).containsExactly("P1");
    }

    @Test
    void price_planWithoutSnapshotIsReportedAsUnquantifiedNotGuessed() {
        // PL2 没快照也没钉住配方: 不应被当成用了该原料
        SupplyImpactDTO.PriceView v = service.price(F, "M1", new BigDecimal("10"));
        assertThat(v.getProducts()).extracting(SupplyImpactDTO.ProductCostChange::getPlanNumber).doesNotContain("PL2");
    }

    private static ProductionPlan plan(String id, String product, String orderId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setPlanNumber(id);
        p.setProductTypeId(product);
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setPlannedDate(LocalDate.now().plusDays(3));
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setPlannedUnit("kg");
        p.setSourceOrderId(orderId);
        return p;
    }

    private static SalesOrder order(String id, String no, String customer) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setOrderNumber(no);
        o.setCustomerName(customer);
        o.setStatus(SalesOrderStatus.CONFIRMED);
        return o;
    }

    private static SalesOrderItem orderItem(String product, String name) {
        SalesOrderItem i = new SalesOrderItem();
        i.setId(1L);
        i.setProductTypeId(product);
        i.setProductName(name);
        i.setQuantity(new BigDecimal("10"));
        i.setDeliveredQuantity(BigDecimal.ZERO);
        i.setUnit("kg");
        return i;
    }
}
