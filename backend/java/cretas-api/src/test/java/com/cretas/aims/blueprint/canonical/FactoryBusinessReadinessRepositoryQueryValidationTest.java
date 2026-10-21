package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
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
 * Real Hibernate context for the business-readiness reads: every count and warehouse route goes
 * through the actual repositories and soft-delete filters, and a fix saved to the database shows
 * up on the next read. Runs on the H2 test profile by default; set CRETAS_WORKFLOW_PG_URL to run
 * the same assertions against PostgreSQL.
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
    @Autowired ShipmentRecordRepository shipmentRepository;

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
                purchaseOrderRepository, productionPlanRepository, shipmentRepository);
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
        RawMaterialType raw = new RawMaterialType();
        raw.setId("RM-READY");
        raw.setFactoryId("F-READY");
        raw.setCode("YL001");
        raw.setName("面粉");
        raw.setCategory("原料");
        raw.setUnit("kg");
        raw.setIsActive(true);
        raw.setCreatedBy(user.getId());
        entityManager.persist(raw);
        entityManager.flush();
    }

    @Test void anotherFactorysSupplierDoesNotCountAndASavedSupplierShowsUpOnTheNextRead() {
        supplier("S-OTHER", "F-OTHER", true);
        customer("C-READY", "F-READY");
        entityManager.flush();

        BusinessDomain before = domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE");
        assertThat(before.state()).isEqualTo("MISSING");
        assertThat(check(before, "ACTIVE_SUPPLIER").state()).isEqualTo("MISSING");
        assertThat(check(before, "ACTIVE_SUPPLIER").remedyKey()).isEqualTo("SUPPLIERS");
        assertThat(check(before, "ACTIVE_RAW_MATERIAL").state()).isEqualTo("MET");
        assertThat(check(before, "PURCHASE_INBOUND_WAREHOUSE").state()).isEqualTo("MET");

        supplier("S-INACTIVE", "F-READY", false);
        entityManager.flush();
        assertThat(check(domain(reader.read("F-READY", capabilities, oneLiveProduct), "PURCHASE"),
                "ACTIVE_SUPPLIER").state()).isEqualTo("MISSING");

        supplier("S-READY", "F-READY", true);
        entityManager.flush();
        entityManager.clear();
        var after = reader.read("F-READY", capabilities, oneLiveProduct);
        assertThat(domain(after, "PURCHASE").state()).isEqualTo("READY");
        assertThat(domain(after, "PRODUCTION").state()).isEqualTo("READY");
        assertThat(domain(after, "SHIPPING").state()).isEqualTo("READY");
        assertThat(after).allSatisfy(row -> assertThat(row.firstRecord().state()).isEqualTo("NONE"));
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
    }

    @Test void factoryWithoutAnyWarehouseRouteIsMissingRatherThanAnError() {
        var other = reader.read("F-OTHER", capabilities, oneLiveProduct);
        assertThat(check(domain(other, "SHIPPING"), "SALES_OUTBOUND_WAREHOUSE").state()).isEqualTo("MISSING");
        assertThat(check(domain(other, "PRODUCTION"), "WORKSHOP_WAREHOUSE").state()).isEqualTo("MISSING");
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

    private static BusinessDomain domain(List<BusinessDomain> rows, String domain) {
        return rows.stream().filter(row -> domain.equals(row.domain())).findFirst().orElseThrow();
    }

    private static Check check(BusinessDomain domain, String code) {
        return domain.checks().stream().filter(row -> code.equals(row.code())).findFirst().orElseThrow();
    }
}
