package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FactoryBusinessReadinessReaderContractTest {
    private final WarehouseResolver resolver = mock(WarehouseResolver.class);
    private final FactoryWarehouseRepository warehouses = mock(FactoryWarehouseRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final RawMaterialTypeRepository raws = mock(RawMaterialTypeRepository.class);
    private final PurchaseOrderRepository purchases = mock(PurchaseOrderRepository.class);
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final SalesDeliveryRecordRepository deliveries = mock(SalesDeliveryRecordRepository.class);
    private final SupplierMaterialRepository relations = mock(SupplierMaterialRepository.class);
    private final FactoryBusinessReadinessReader reader = new FactoryBusinessReadinessReader(resolver, warehouses,
            suppliers, customers, raws, purchases, plans, deliveries, relations);
    private final List<ProductRuntime> live = List.of(new ProductRuntime("p1", "盒装", "PUBLISHED", 7L, 1, "b", 1, "匹配"));
    private final List<CapabilityView> all = List.of(included("materials"), included("processes"), included("products"));

    @BeforeEach void readyFactory() {
        when(suppliers.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(supplier("S1")));
        when(raws.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(material("M1")));
        when(relations.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc("FA", "S1"))
                .thenReturn(List.of(relation("S1", "M1", true)));
        when(customers.countByFactoryIdAndIsActive("FA", true)).thenReturn(2L);
        when(resolver.resolvePurchaseInboundWh("FA")).thenReturn("wh-log");
        when(resolver.resolveSalesOutboundWh("FA")).thenReturn("wh-log");
        when(resolver.resolveProductionRawWh("FA")).thenReturn("wh-log");
        when(resolver.resolveWorkshopId("FA")).thenReturn("wh-wks");
        when(warehouses.findByIdAndFactoryIdAndDeletedAtIsNull(any(), eq("FA")))
                .thenAnswer(call -> Optional.of(warehouse(call.getArgument(0), true)));
        Page<com.cretas.aims.entity.inventory.PurchaseOrder> none = new PageImpl<>(List.of(), Pageable.ofSize(1), 0);
        when(purchases.findByFactoryIdOrderByCreatedAtDesc(eq("FA"), any())).thenReturn(none);
        when(purchases.findByFactoryIdAndStatusOrderByCreatedAtDesc(eq("FA"), eq(PurchaseOrderStatus.COMPLETED), any()))
                .thenReturn(none);
        when(deliveries.findByFactoryIdOrderByCreatedAtDesc(eq("FA"), any())).thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(1), 0));
        when(deliveries.findByFactoryIdAndStatusIn(eq("FA"), any(), any())).thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(1), 0));
    }

    @Test void readyFactoryIsReadyForEachSelectedBusinessButFirstRecordStaysSeparate() {
        var rows = reader.read("FA", all, live);
        assertThat(rows).extracting(BusinessDomain::state).containsExactly("READY", "READY", "READY");
        assertThat(rows).extracting(row -> row.firstRecord().state()).containsExactly("NONE", "NONE", "NONE");
        // Documents existing (any status) are only RECORDED; completion needs the terminal status.
        when(plans.countByFactoryId("FA")).thenReturn(4L);
        var recorded = domain(reader.read("FA", all, live), "PRODUCTION").firstRecord();
        assertThat(recorded.state()).isEqualTo("RECORDED");
        assertThat(recorded.completedCount()).isZero();
        when(plans.countByFactoryIdAndStatus("FA", ProductionPlanStatus.COMPLETED)).thenReturn(1L);
        var completed = domain(reader.read("FA", all, live), "PRODUCTION").firstRecord();
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.count()).isEqualTo(4L);
        assertThat(completed.completedCount()).isEqualTo(1L);
    }

    @Test void independentSuppliersAndMaterialsWithoutAValidRelationshipAreNotReadyToPurchase() {
        when(relations.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc("FA", "S1"))
                .thenReturn(List.of(relation("S1", "M1", false), relation("S1", "M-INACTIVE", true)));
        BusinessDomain purchase = domain(reader.read("FA", all, live), "PURCHASE");
        assertThat(check(purchase, "ACTIVE_SUPPLIER_MATERIAL").state()).isEqualTo("MISSING");
        assertThat(purchase.state()).isEqualTo("MISSING");
        assertThat(purchase.checks()).extracting(Check::code).doesNotContain("ACTIVE_SUPPLIER", "ACTIVE_RAW_MATERIAL");
    }

    @Test void publishedButMissingSupplierPointsAtTheSupplierPage() {
        when(suppliers.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of());
        BusinessDomain purchase = domain(reader.read("FA", all, live), "PURCHASE");
        assertThat(purchase.state()).isEqualTo("MISSING");
        Check missing = check(purchase, "ACTIVE_SUPPLIER_MATERIAL");
        assertThat(missing.state()).isEqualTo("MISSING");
        assertThat(missing.remedyKey()).isEqualTo("SUPPLIERS");
    }

    @Test void unselectedBusinessIsNotApplicableAndNoRdOrBoxRuleIsDemanded() {
        var rows = reader.read("FA", List.of(included("products"), excluded("materials")), live);
        assertThat(domain(rows, "PURCHASE").applicability()).isEqualTo("NOT_APPLICABLE");
        assertThat(domain(rows, "PURCHASE").state()).isEqualTo("NOT_APPLICABLE");
        assertThat(domain(rows, "PRODUCTION").state()).isEqualTo("NOT_APPLICABLE");
        assertThat(domain(rows, "SHIPPING").state()).isEqualTo("READY");
        assertThat(rows).flatExtracting(BusinessDomain::checks).extracting(Check::code)
                .doesNotContain("RD_WAREHOUSE", "PACKAGING_SPEC");
        assertThat(domain(rows, "PRODUCTION").notChecked()).anyMatch(note -> note.contains("研发仓"));
        assertThat(domain(rows, "PURCHASE").notChecked()).anyMatch(note -> note.contains("基本单位采购无需包装换算"));
    }

    @Test void unreadableFactsAreUnknownNeverReady() {
        when(customers.countByFactoryIdAndIsActive("FA", true)).thenThrow(new IllegalStateException("db down"));
        when(deliveries.findByFactoryIdOrderByCreatedAtDesc(eq("FA"), any())).thenThrow(new IllegalStateException("db down"));
        BusinessDomain shipping = domain(reader.read("FA", all, live), "SHIPPING");
        assertThat(check(shipping, "ACTIVE_CUSTOMER").state()).isEqualTo("UNKNOWN");
        assertThat(shipping.state()).isEqualTo("UNKNOWN");
        assertThat(shipping.firstRecord().state()).isEqualTo("UNKNOWN");
        assertThat(shipping.firstRecord().count()).isNull();
        assertThat(shipping.firstRecord().completedCount()).isNull();
        assertThat(domain(reader.read("FA", List.of(), live), "SHIPPING").applicability()).isEqualTo("UNKNOWN");
    }

    @Test void missingRouteOrStoppedWarehouseIsMissingAndUnexpectedFailureIsUnknown() {
        when(resolver.resolveWorkshopId("FA")).thenThrow(new BusinessException(500, "no seed"));
        when(warehouses.findByIdAndFactoryIdAndDeletedAtIsNull("wh-log", "FA"))
                .thenReturn(Optional.of(warehouse("wh-log", false)));
        when(resolver.resolveSalesOutboundWh("FA")).thenThrow(new IllegalStateException("timeout"));
        var rows = reader.read("FA", all, live);
        assertThat(check(domain(rows, "PRODUCTION"), "WORKSHOP_WAREHOUSE").state()).isEqualTo("MISSING");
        assertThat(check(domain(rows, "PURCHASE"), "PURCHASE_INBOUND_WAREHOUSE").state()).isEqualTo("MISSING");
        assertThat(check(domain(rows, "SHIPPING"), "SALES_OUTBOUND_WAREHOUSE").state()).isEqualTo("UNKNOWN");
    }

    @Test void noPublishedProductRuntimeBlocksProductionAndShipping() {
        var rows = reader.read("FA", all, List.of(new ProductRuntime("p1", "盒装", "NEEDS_REVIEW", null, null, null, null, "未匹配")));
        assertThat(check(domain(rows, "PRODUCTION"), "PUBLISHED_PRODUCT_RUNTIME").state()).isEqualTo("MISSING");
        var empty = reader.read("FA", all, List.of());
        assertThat(check(domain(empty, "SHIPPING"), "SALEABLE_PRODUCT").state()).isEqualTo("MISSING");
        assertThat(check(domain(empty, "PRODUCTION"), "PUBLISHED_PRODUCT_RUNTIME").remedyKey()).isEqualTo("PRODUCTS");
    }

    @Test void readsNeverWrite() {
        reader.read("FA", all, live);
        verify(suppliers, never()).save(any());
        verify(customers, never()).save(any());
        verify(warehouses, never()).save(any());
        verify(purchases, never()).save(any());
    }

    private static Supplier supplier(String id) {
        Supplier supplier = new Supplier(); supplier.setId(id); supplier.setFactoryId("FA"); supplier.setIsActive(true);
        return supplier;
    }
    private static RawMaterialType material(String id) {
        RawMaterialType material = new RawMaterialType(); material.setId(id); material.setFactoryId("FA"); material.setIsActive(true);
        return material;
    }
    private static SupplierMaterial relation(String supplierId, String materialId, boolean active) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setFactoryId("FA"); relation.setSupplierId(supplierId); relation.setMaterialTypeId(materialId); relation.setActive(active);
        return relation;
    }
    private static CapabilityView included(String id) { return CapabilityView.builder().id(id).included(true).build(); }
    private static CapabilityView excluded(String id) { return CapabilityView.builder().id(id).included(false).build(); }
    private static FactoryWarehouse warehouse(String id, boolean active) {
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId(id); warehouse.setFactoryId("FA"); warehouse.setName(id); warehouse.setIsActive(active);
        return warehouse;
    }
    private static BusinessDomain domain(List<BusinessDomain> rows, String domain) {
        return rows.stream().filter(row -> domain.equals(row.domain())).findFirst().orElseThrow();
    }
    private static Check check(BusinessDomain domain, String code) {
        return domain.checks().stream().filter(row -> code.equals(row.code())).findFirst().orElseThrow();
    }
}
