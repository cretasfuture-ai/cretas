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
    private final com.cretas.aims.repository.UserRepository users = mock(com.cretas.aims.repository.UserRepository.class);
    private final com.cretas.aims.repository.material.MaterialPackagingSpecRepository packagingSpecs =
            mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class);
    private final com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository purchaseSpecs =
            mock(com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository.class);
    private final com.cretas.aims.service.unit.UnitContractService units =
            mock(com.cretas.aims.service.unit.UnitContractService.class);
    private final FactoryBusinessReadinessReader reader = new FactoryBusinessReadinessReader(resolver, warehouses,
            suppliers, customers, raws, purchases, plans, deliveries, relations, users, packagingSpecs,
            purchaseSpecs, units, realPermissions());
    private final List<ProductRuntime> live = List.of(new ProductRuntime("p1", "盒装", "PUBLISHED", 7L, 1, "b", 1, "匹配"));
    private final List<CapabilityView> all = List.of(included("materials"), included("processes"), included("products"));

    @BeforeEach void readyFactory() {
        when(suppliers.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(supplier("S1")));
        when(raws.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(material("M1")));
        when(relations.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc("FA", "S1"))
                .thenReturn(List.of(relation("S1", "M1", true)));
        when(customers.countByFactoryIdAndIsActive("FA", true)).thenReturn(2L);
        when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(
                holder("procurement_manager"), holder("workshop_supervisor"), holder("sales_manager")));
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

    @Test void packagePurchaseWithoutConversionIsMissingButBaseUnitPurchaseIsNotDemandedOne() {
        RawMaterialType box = material("M1");
        box.setName("面粉"); box.setUnit("kg");
        when(raws.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(box));
        when(units.normalize(eq("FA"), any())).thenAnswer(call -> null);
        SupplierMaterial byBag = relation("S1", "M1", true);
        byBag.setId("R1"); byBag.setPurchaseUnit("袋");
        when(relations.findByFactoryIdAndSupplierIdOrderByActiveDescPreferredDescCreatedAtDesc("FA", "S1"))
                .thenReturn(List.of(byBag));
        BusinessDomain purchase = domain(reader.read("FA", all, live), "PURCHASE");
        assertThat(check(purchase, "PACKAGING_PURCHASE_CONVERSION").state()).isEqualTo("MISSING");
        assertThat(check(purchase, "PACKAGING_PURCHASE_CONVERSION").detail()).contains("面粉").contains("袋");
        assertThat(purchase.state()).isEqualTo("MISSING");

        var spec = new com.cretas.aims.entity.material.MaterialPackagingSpec();
        spec.setPackageUnit("袋"); spec.setActive(true);
        when(packagingSpecs.findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc("FA", "M1"))
                .thenReturn(List.of(spec));
        assertThat(check(domain(reader.read("FA", all, live), "PURCHASE"), "PACKAGING_PURCHASE_CONVERSION").state())
                .isEqualTo("MET");

        byBag.setPurchaseUnit("kg");
        when(packagingSpecs.findByFactoryIdAndMaterialTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc("FA", "M1"))
                .thenReturn(List.of());
        assertThat(check(domain(reader.read("FA", all, live), "PURCHASE"), "PACKAGING_PURCHASE_CONVERSION").state())
                .as("基本单位采购不强求包装换算").isEqualTo("MET");
    }

    @Test void readyDomainsOfferTheFirstBusinessActionCarryingRealIdsAndMissingOnesOfferNone() {
        var customer = new com.cretas.aims.entity.Customer();
        customer.setId("C1"); customer.setFactoryId("FA"); customer.setIsActive(true);
        when(customers.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(customer));
        var rows = reader.read("FA", all, live);
        var purchase = domain(rows, "PURCHASE").firstAction();
        assertThat(purchase.path()).isEqualTo("/procurement/orders");
        assertThat(purchase.query()).containsEntry("supplierId", "S1").containsEntry("materialTypeId", "M1");
        var sales = domain(rows, "SHIPPING").firstAction();
        assertThat(sales.path()).isEqualTo("/sales/orders/new");
        assertThat(sales.query()).containsEntry("customerId", "C1").containsEntry("productId", "p1");
        assertThat(domain(rows, "PRODUCTION").firstAction().query()).containsEntry("productTypeId", "p1");

        when(suppliers.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of());
        var blocked = reader.read("FA", all, live);
        assertThat(domain(blocked, "PURCHASE").state()).isEqualTo("MISSING");
        assertThat(domain(blocked, "PURCHASE").firstAction()).as("缺项时不给「去下单」入口").isNull();
    }

    @Test void aDomainWithNobodyInThePostIsMissingAndInactiveHoldersDoNotCount() {
        var inactive = holder("procurement_manager");
        inactive.setIsActive(false);
        // 采购 / 生产两个岗位的人停用了或不在, 只剩销售经理在岗
        when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(inactive, holder("sales_manager")));
        var rows = reader.read("FA", all, live);
        assertThat(check(domain(rows, "PURCHASE"), "PURCHASE_POST_HOLDER").state()).isEqualTo("MISSING");
        assertThat(check(domain(rows, "PURCHASE"), "PURCHASE_POST_HOLDER").remedyKey()).isEqualTo("USERS");
        assertThat(check(domain(rows, "PRODUCTION"), "PRODUCTION_POST_HOLDER").state()).isEqualTo("MISSING");
        assertThat(check(domain(rows, "SHIPPING"), "SHIPPING_POST_HOLDER").state())
                .as("销售经理仍在岗").isEqualTo("MET");
        when(users.findByFactoryIdAndIsActive("FA", true)).thenThrow(new IllegalStateException("db"));
        assertThat(check(domain(reader.read("FA", all, live), "SHIPPING"), "SHIPPING_POST_HOLDER").state())
                .isEqualTo("UNKNOWN");
    }

    /** B5: 一人厂 (只有厂长) 具备下单 / 报工 / 发货权限, 三个检查都应该是 MET, 不能永远「未就绪」。 */
    @Test void aOnePersonFactoryWhereOnlyTheFactoryHeadExistsIsReady() {
        when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(holder("factory_super_admin")));
        var rows = reader.read("FA", all, live);
        assertThat(check(domain(rows, "PURCHASE"), "PURCHASE_POST_HOLDER").state()).isEqualTo("MET");
        assertThat(check(domain(rows, "PRODUCTION"), "PRODUCTION_POST_HOLDER").state()).isEqualTo("MET");
        assertThat(check(domain(rows, "SHIPPING"), "SHIPPING_POST_HOLDER").state()).isEqualTo("MET");
    }

    /** B5 阴性对照: 没有相关权限的岗位 (人事 / 财务) 不算有人能干这件事。 */
    @Test void holdersWithoutTheRelevantPermissionDoNotCount() {
        when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(
                holder("hr_admin"), holder("quality_inspector")));
        var rows = reader.read("FA", all, live);
        assertThat(check(domain(rows, "PURCHASE"), "PURCHASE_POST_HOLDER").state()).isEqualTo("MISSING");
        assertThat(check(domain(rows, "PRODUCTION"), "PRODUCTION_POST_HOLDER").state()).isEqualTo("MISSING");
        assertThat(check(domain(rows, "SHIPPING"), "SHIPPING_POST_HOLDER").state()).isEqualTo("MISSING");
    }

    /** B5: 原来点名的岗位全部仍然算 (默认等于现有名单, 上线后行为不变)。 */
    @Test void everyRoleNamedByTheOldListStillCounts() {
        for (String role : List.of("procurement_manager", "warehouse_manager")) {
            when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(holder(role)));
            assertThat(check(domain(reader.read("FA", all, live), "PURCHASE"), "PURCHASE_POST_HOLDER").state())
                    .as(role).isEqualTo("MET");
        }
        for (String role : List.of("workshop_supervisor", "operator")) {
            when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(holder(role)));
            assertThat(check(domain(reader.read("FA", all, live), "PRODUCTION"), "PRODUCTION_POST_HOLDER").state())
                    .as(role).isEqualTo("MET");
        }
        for (String role : List.of("sales_manager", "warehouse_manager")) {
            when(users.findByFactoryIdAndIsActive("FA", true)).thenReturn(List.of(holder(role)));
            assertThat(check(domain(reader.read("FA", all, live), "SHIPPING"), "SHIPPING_POST_HOLDER").state())
                    .as(role).isEqualTo("MET");
        }
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
    private static com.cretas.aims.entity.User holder(String role) {
        var user = new com.cretas.aims.entity.User();
        user.setId((long) role.hashCode()); user.setFactoryId("FA"); user.setRoleCode(role); user.setIsActive(true);
        return user;
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

    /** 真实的权限矩阵 (不是桩): 仓库层返回「成功的空结果」, 落到内置矩阵判定。 */
    private static com.cretas.aims.service.impl.PermissionServiceImpl realPermissions() {
        com.cretas.aims.service.impl.PermissionServiceImpl service = new com.cretas.aims.service.impl.PermissionServiceImpl();
        com.cretas.aims.testutil.PermissionRepositoryFixtures.installSuccessfulEmpty(service);
        return service;
    }
}
