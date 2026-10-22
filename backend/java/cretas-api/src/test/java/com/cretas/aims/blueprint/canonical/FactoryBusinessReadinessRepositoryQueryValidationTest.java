package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate context for the business-readiness reads: every count, supply relationship and
 * warehouse route goes through the actual repositories and soft-delete filters, and a fix saved
 * to the database shows up on the next read. Runs on the H2 test profile by default; set
 * CRETAS_WORKFLOW_PG_URL to run the same assertions against PostgreSQL.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class FactoryBusinessReadinessRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            // Production binds jsonb columns (e.g. factories.inference_data) with stringtype=unspecified.
            String bound = url.contains("stringtype=") ? url : url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
            registry.add("spring.datasource.url", () -> bound);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired TestEntityManager entityManager;
    @Autowired FactoryWarehouseRepository warehouseRepository;
    @Autowired FactoryWarehouseDefaultRepository defaultRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired RawMaterialTypeRepository rawMaterialRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired ProductionPlanRepository productionPlanRepository;
    @Autowired SalesDeliveryRecordRepository deliveryRepository;
    @Autowired SupplierMaterialRepository supplierMaterialRepository;

    private FactoryBusinessReadinessReader reader;
    private User user;
    private final List<CapabilityView> capabilities = List.of(
            CapabilityView.builder().id("materials").included(true).build(),
            CapabilityView.builder().id("processes").included(true).build(),
            CapabilityView.builder().id("products").included(true).build());
    private final List<ProductRuntime> oneLiveProduct = List.of(
            new ProductRuntime("p1", "盒装", "PUBLISHED", 7L, 1, "bom", 1, "匹配"));

    @BeforeEach void setup() {
        reader = new FactoryBusinessReadinessReader(new WarehouseResolver(warehouseRepository, defaultRepository),
                warehouseRepository, supplierRepository, customerRepository, rawMaterialRepository,
                purchaseOrderRepository, productionPlanRepository, deliveryRepository, supplierMaterialRepository);
        factory("F-READY");
        factory("F-OTHER");
        user = new User();
        user.setFactoryId("F-READY");
        user.setUsername("readiness-jpa-user");
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        entityManager.persist(user);
        warehouse("WH-LOG-READY", "F-READY", "WH-LOG", "主仓", FactoryWarehouse.WarehouseType.LOGISTICS, true);
        warehouse("WH-WKS-READY", "F-READY", "WH-WKS", "生产仓", FactoryWarehouse.WarehouseType.WORKSHOP, true);
        material("RM-READY", "F-READY", "YL001", true);
        material("RM-OFF", "F-READY", "YL002", false);
        material("RM-OTHER", "F-OTHER", "YL001", true);
        entityManager.flush();
    }

    @Test void purchaseNeedsAnActiveSameFactoryRelationshipBetweenAnActiveSupplierAndAnActiveMaterial() {
        supplier("S-READY", "F-READY", true);
        supplier("S-OFF", "F-READY", false);
        supplier("S-OTHER", "F-OTHER", true);
        customer("C-READY", "F-READY");
        entityManager.flush();
        // Active supplier and active material each exist, but nothing links them: not ready.
        assertThat(relationship()).isEqualTo("MISSING");

        relation("SM-INACTIVE", "F-READY", "S-READY", "RM-READY", false);   // relationship switched off
        relation("SM-SUPPLIER-OFF", "F-READY", "S-OFF", "RM-READY", true);  // supplier paused
        relation("SM-MATERIAL-OFF", "F-READY", "S-READY", "RM-OFF", true);  // material disabled
        relation("SM-FOREIGN", "F-OTHER", "S-OTHER", "RM-OTHER", true);     // another factory's relation
        entityManager.flush();
        assertThat(relationship()).isEqualTo("MISSING");

        // The fix a factory admin makes: switch the existing relationship back on (one row per supplier+material).
        entityManager.find(SupplierMaterial.class, "SM-INACTIVE").setActive(true);
        entityManager.flush();
        entityManager.clear();
        BusinessDomain purchase = domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE");
        assertThat(check(purchase, "ACTIVE_SUPPLIER_MATERIAL").state()).isEqualTo("MET");
        assertThat(check(purchase, "ACTIVE_SUPPLIER_MATERIAL").detail()).isEqualTo("当前 1 个");
        assertThat(purchase.state()).isEqualTo("READY");
        assertThat(purchase.notChecked()).anyMatch(note -> note.contains("采购价格"));
    }

    @Test void draftPurchaseOrdersExistButDoNotCompleteTheFirstBusinessAFullyReceivedOneDoes() {
        supplier("S-READY", "F-READY", true);
        entityManager.flush();
        purchaseOrder("PO-DRAFT-1", PurchaseOrderStatus.DRAFT);
        purchaseOrder("PO-DRAFT-2", PurchaseOrderStatus.PARTIAL_RECEIVED);
        entityManager.flush();
        entityManager.clear();

        var draftsOnly = domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE").firstRecord();
        assertThat(draftsOnly.state()).isEqualTo("RECORDED");
        assertThat(draftsOnly.count()).isEqualTo(2L);
        assertThat(draftsOnly.completedCount()).isZero();
        assertThat(draftsOnly.detail()).contains("首笔业务未完成");

        purchaseOrder("PO-DONE", PurchaseOrderStatus.COMPLETED);
        entityManager.flush();
        entityManager.clear();
        var completed = domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE").firstRecord();
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.count()).isEqualTo(3L);
        assertThat(completed.completedCount()).isEqualTo(1L);
        // No production plan or delivery: those domains have no first business at all.
        var rows = reader.read("F-READY", capabilities, oneLiveProduct);
        assertThat(domain(rows, "PRODUCTION").firstRecord().state()).isEqualTo("NONE");
        assertThat(domain(rows, "SHIPPING").firstRecord().state()).isEqualTo("NONE");
    }

    @Test void configuredDefaultPointingAtAStoppedWarehouseIsMissingNotReady() {
        warehouse("WH-STOPPED", "F-READY", "WH-STOP", "停用仓", FactoryWarehouse.WarehouseType.RAW, false);
        FactoryWarehouseDefault configured = new FactoryWarehouseDefault();
        configured.setId("D-PURCHASE");
        configured.setFactoryId("F-READY");
        configured.setPurpose(WarehouseDefaultPurpose.PURCHASE_INBOUND_DEFAULT);
        configured.setWarehouseId("WH-STOPPED");
        entityManager.persist(configured);
        entityManager.flush();

        Check inbound = check(domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE"),
                "PURCHASE_INBOUND_WAREHOUSE");
        assertThat(inbound.state()).isEqualTo("MISSING");
        assertThat(inbound.detail()).contains("停用仓");
        assertThat(inbound.remedyKey()).isEqualTo("WAREHOUSE_DEFAULTS");
    }

    @Test void factoryWithoutAnyWarehouseRouteIsMissingRatherThanAnError() {
        var other = reader.read("F-OTHER", capabilities, oneLiveProduct);
        assertThat(check(domain(other, "SHIPPING"), "SALES_OUTBOUND_WAREHOUSE").state()).isEqualTo("MISSING");
        assertThat(check(domain(other, "PRODUCTION"), "WORKSHOP_WAREHOUSE").state()).isEqualTo("MISSING");
    }

    private String relationship() {
        return check(domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE"),
                "ACTIVE_SUPPLIER_MATERIAL").state();
    }

    private void factory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName("readiness " + id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private void warehouse(String id, String factoryId, String code, String name,
            FactoryWarehouse.WarehouseType type, boolean active) {
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId(id);
        warehouse.setFactoryId(factoryId);
        warehouse.setCode(code);
        warehouse.setName(name);
        warehouse.setType(type);
        warehouse.setIsActive(active);
        entityManager.persist(warehouse);
    }

    private void material(String id, String factoryId, String code, boolean active) {
        RawMaterialType raw = new RawMaterialType();
        raw.setId(id);
        raw.setFactoryId(factoryId);
        raw.setCode(code);
        raw.setName("物料 " + id);
        raw.setCategory("原料");
        raw.setUnit("kg");
        raw.setIsActive(active);
        raw.setCreatedBy(user.getId());
        entityManager.persist(raw);
    }

    private void supplier(String id, String factoryId, boolean active) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFactoryId(factoryId);
        supplier.setCode(id);
        supplier.setSupplierCode(id);
        supplier.setName("供应商 " + id);
        supplier.setIsActive(active);
        supplier.setCreatedBy(user.getId());
        entityManager.persist(supplier);
    }

    private void relation(String id, String factoryId, String supplierId, String materialId, boolean active) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId(id);
        relation.setFactoryId(factoryId);
        relation.setSupplierId(supplierId);
        relation.setMaterialTypeId(materialId);
        relation.setPurchaseUnit("kg");
        relation.setActive(active);
        entityManager.persist(relation);
    }

    private void customer(String id, String factoryId) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFactoryId(factoryId);
        customer.setCode(id);
        customer.setCustomerCode(id);
        customer.setName("客户 " + id);
        customer.setIsActive(true);
        customer.setCreatedBy(user.getId());
        entityManager.persist(customer);
    }

    private void purchaseOrder(String id, PurchaseOrderStatus status) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setFactoryId("F-READY");
        order.setSupplierId("S-READY");
        order.setOrderNumber(id);
        order.setOrderDate(LocalDate.now());
        order.setStatus(status);
        order.setCreatedBy(user.getId());
        entityManager.persist(order);
    }

    private static BusinessDomain domain(List<BusinessDomain> rows, String domain) {
        return rows.stream().filter(row -> domain.equals(row.domain())).findFirst().orElseThrow();
    }

    private static Check check(BusinessDomain domain, String code) {
        return domain.checks().stream().filter(row -> code.equals(row.code())).findFirst().orElseThrow();
    }
}
