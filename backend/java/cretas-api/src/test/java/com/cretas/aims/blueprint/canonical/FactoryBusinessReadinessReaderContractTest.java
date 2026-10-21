package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
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
    private final ShipmentRecordRepository shipments = mock(ShipmentRecordRepository.class);
    private final FactoryBusinessReadinessReader reader = new FactoryBusinessReadinessReader(resolver, warehouses,
            suppliers, customers, raws, purchases, plans, shipments);
    private final List<ProductRuntime> live = List.of(new ProductRuntime("p1", "盒装", "PUBLISHED", 7L, 1, "b", 1, "匹配"));
    private final List<CapabilityView> all = List.of(included("materials"), included("processes"), included("products"));

    @BeforeEach void readyFactory() {
        when(suppliers.countByFactoryIdAndIsActive("FA", true)).thenReturn(1L);
        when(customers.countByFactoryIdAndIsActive("FA", true)).thenReturn(2L);
        when(raws.countByFactoryIdAndIsActive("FA", true)).thenReturn(3L);
        when(resolver.resolvePurchaseInboundWh("FA")).thenReturn("wh-log");
        when(resolver.resolveSalesOutboundWh("FA")).thenReturn("wh-log");
        when(resolver.resolveProductionRawWh("FA")).thenReturn("wh-log");
        when(resolver.resolveWorkshopId("FA")).thenReturn("wh-wks");
        when(warehouses.findByIdAndFactoryIdAndDeletedAtIsNull(any(), eq("FA")))
                .thenAnswer(call -> Optional.of(warehouse(call.getArgument(0), true)));
        Page<com.cretas.aims.entity.inventory.PurchaseOrder> none = new PageImpl<>(List.of(), Pageable.ofSize(1), 0);
        when(purchases.findByFactoryIdOrderByCreatedAtDesc(eq("FA"), any())).thenReturn(none);
    }

    @Test void readyFactoryIsReadyForEachSelectedBusinessButFirstRecordStaysSeparate() {
        var rows = reader.read("FA", all, live);
        assertThat(rows).extracting(BusinessDomain::state).containsExactly("READY", "READY", "READY");
        assertThat(rows).extracting(row -> row.firstRecord().state()).containsExactly("NONE", "NONE", "NONE");
        when(plans.countByFactoryId("FA")).thenReturn(4L);
        assertThat(domain(reader.read("FA", all, live), "PRODUCTION").firstRecord().state()).isEqualTo("RECORDED");
    }

    @Test void publishedButMissingSupplierPointsAtTheSupplierPage() {
        when(suppliers.countByFactoryIdAndIsActive("FA", true)).thenReturn(0L);
        BusinessDomain purchase = domain(reader.read("FA", all, live), "PURCHASE");
        assertThat(purchase.state()).isEqualTo("MISSING");
        Check missing = check(purchase, "ACTIVE_SUPPLIER");
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
        when(shipments.countByFactoryId("FA")).thenThrow(new IllegalStateException("db down"));
        BusinessDomain shipping = domain(reader.read("FA", all, live), "SHIPPING");
        assertThat(check(shipping, "ACTIVE_CUSTOMER").state()).isEqualTo("UNKNOWN");
        assertThat(shipping.state()).isEqualTo("UNKNOWN");
        assertThat(shipping.firstRecord().state()).isEqualTo("UNKNOWN");
        assertThat(shipping.firstRecord().count()).isNull();
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
