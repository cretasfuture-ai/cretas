package com.cretas.aims.blueprint.interview;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.FactoryBusinessReadinessReader;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.BusinessDomain;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.Check;
import com.cretas.aims.blueprint.canonical.FactoryReadinessReport.ProductRuntime;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouseDefault;
import com.cretas.aims.entity.factory.WarehouseDefaultPurpose;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseDefaultRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.service.factory.FactoryWarehouseService;
import com.cretas.aims.service.factory.WarehouseResolver;
import java.util.List;
import java.util.function.Function;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Hibernate context for the warehouse step of blueprint apply: the warehouses are created
 * through {@link FactoryWarehouseService}, the default purposes through the actual repositories,
 * and the result is read back through the same {@link WarehouseResolver} the business paths and
 * the readiness report use. Runs on the H2 test profile by default; set CRETAS_WORKFLOW_PG_URL to
 * run the same assertions against PostgreSQL.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class BlueprintWarehouseDefaultsRepositoryQueryValidationTest {

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

    private static final String F = "F-BP-WH";

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

    private WarehouseResolver resolver;
    private FactoryBuildApplyService apply;
    private FactoryBusinessReadinessReader reader;
    private final List<CapabilityView> capabilities = List.of(
            CapabilityView.builder().id("materials").included(true).build(),
            CapabilityView.builder().id("processes").included(true).build(),
            CapabilityView.builder().id("products").included(true).build());
    private final List<ProductRuntime> oneLiveProduct = List.of(
            new ProductRuntime("p1", "盒装", "PUBLISHED", 7L, 1, "bom", 1, "匹配"));

    @BeforeEach void setup() {
        resolver = new WarehouseResolver(warehouseRepository, defaultRepository);
        apply = new FactoryBuildApplyService();
        ReflectionTestUtils.setField(apply, "factoryWarehouseService", new FactoryWarehouseService(warehouseRepository));
        ReflectionTestUtils.setField(apply, "warehouseDefaultsWriter",
                new BlueprintWarehouseDefaultsWriter(warehouseRepository, defaultRepository, resolver));
        reader = new FactoryBusinessReadinessReader(resolver, warehouseRepository, supplierRepository,
                customerRepository, rawMaterialRepository, purchaseOrderRepository, productionPlanRepository,
                deliveryRepository, supplierMaterialRepository);
        Factory factory = new Factory();
        factory.setId(F);
        factory.setName("blueprint warehouses");
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
        entityManager.flush();
    }

    @Test void appliedBlueprintRoutesEveryBusinessPathToItsOwnWarehousesAndReapplyIsIdempotent() {
        ApplyAIInitRequest request = request(
                item("WH-RAW", "原料仓", "RAW"), item("WH-PROD", "生产仓", "WORKSHOP"),
                item("WH-WIP", "半成品仓", "WIP"), item("WH-FG", "成品仓", "FINISHED"));

        ApplyAIInitResult result = new ApplyAIInitResult();
        apply.applyWarehouses(F, request, result);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.getWrittenWarehouses()).containsExactly("WH-RAW", "WH-PROD", "WH-WIP", "WH-FG");
        assertThat(result.getWrittenWarehouseDefaults()).hasSize(4);
        assertThat(code(resolver::resolvePurchaseInboundWh)).isEqualTo("WH-RAW");
        assertThat(code(resolver::resolveProductionRawWh)).isEqualTo("WH-RAW");
        assertThat(code(resolver::resolveWorkshopId)).isEqualTo("WH-PROD");
        assertThat(code(resolver::resolveSalesOutboundWh)).isEqualTo("WH-FG");

        List<BusinessDomain> readiness = reader.read(F, capabilities, oneLiveProduct);
        assertThat(check(readiness, "PURCHASE", "PURCHASE_INBOUND_WAREHOUSE").state()).isEqualTo("MET");
        assertThat(check(readiness, "PRODUCTION", "WORKSHOP_WAREHOUSE").state()).isEqualTo("MET");
        assertThat(check(readiness, "PRODUCTION", "PRODUCTION_RAW_WAREHOUSE").state()).isEqualTo("MET");
        assertThat(check(readiness, "SHIPPING", "SALES_OUTBOUND_WAREHOUSE").state()).isEqualTo("MET");
        assertThat(check(readiness, "SHIPPING", "SALES_OUTBOUND_WAREHOUSE").detail()).contains("成品仓");

        List<String> before = defaultRepository.findByFactoryIdAndDeletedAtIsNull(F).stream()
                .map(FactoryWarehouseDefault::getId).sorted().toList();
        ApplyAIInitResult again = new ApplyAIInitResult();
        apply.applyWarehouses(F, request, again);
        entityManager.flush();
        entityManager.clear();
        assertThat(again.getSkippedWarehouses()).containsExactly("WH-RAW", "WH-PROD", "WH-WIP", "WH-FG");
        assertThat(again.getWrittenWarehouseDefaults()).isEmpty();
        assertThat(defaultRepository.findByFactoryIdAndDeletedAtIsNull(F).stream()
                .map(FactoryWarehouseDefault::getId).sorted().toList()).isEqualTo(before).hasSize(4);
    }

    @Test void demoShapedBlueprintWithoutAProductionWarehouseRoutesPurchaseAndShippingButNotWorkshop() {
        // Same shape as DEMO_2026_001 in production: 原料仓 RAW / 半成品仓 WIP / 成品仓 FINISHED.
        ApplyAIInitResult result = new ApplyAIInitResult();
        apply.applyWarehouses(F, request(item("WH-RAW", "原料仓", "RAW"),
                item("WH-WIP", "半成品仓", "WIP"), item("WH-FG", "成品仓", "FINISHED")), result);
        entityManager.flush();
        entityManager.clear();

        List<BusinessDomain> readiness = reader.read(F, capabilities, oneLiveProduct);
        assertThat(check(readiness, "PURCHASE", "PURCHASE_INBOUND_WAREHOUSE").state()).isEqualTo("MET");
        assertThat(check(readiness, "PRODUCTION", "PRODUCTION_RAW_WAREHOUSE").state()).isEqualTo("MET");
        assertThat(check(readiness, "SHIPPING", "SALES_OUTBOUND_WAREHOUSE").state()).isEqualTo("MET");
        // WIP is not a production warehouse for the report/settlement gates, so it stays an honest gap.
        assertThat(check(readiness, "PRODUCTION", "WORKSHOP_WAREHOUSE").state()).isEqualTo("MISSING");
        assertThat(result.getSkippedWarehouseDefaults()).containsExactly("WORKSHOP_DEFAULT(蓝图没有启用的 WORKSHOP 仓)");
    }

    @Test void aDefaultAnAdminSavedBeforeApplyIsLeftExactlyAsItWas() {
        FactoryWarehouse chosen = new FactoryWarehouse();
        chosen.setId("WH-ADMIN-ID");
        chosen.setFactoryId(F);
        chosen.setCode("WH-ADMIN");
        chosen.setName("管理员选的发货仓");
        chosen.setType(FactoryWarehouse.WarehouseType.TEMP);
        chosen.setIsActive(true);
        entityManager.persist(chosen);
        FactoryWarehouseDefault saved = new FactoryWarehouseDefault();
        saved.setId("ADMIN-SALES");
        saved.setFactoryId(F);
        saved.setPurpose(WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT);
        saved.setWarehouseId("WH-ADMIN-ID");
        entityManager.persist(saved);
        entityManager.flush();

        ApplyAIInitResult result = new ApplyAIInitResult();
        apply.applyWarehouses(F, request(item("WH-RAW", "原料仓", "RAW"), item("WH-FG", "成品仓", "FINISHED")), result);
        entityManager.flush();
        entityManager.clear();

        assertThat(code(resolver::resolveSalesOutboundWh)).isEqualTo("WH-ADMIN");
        assertThat(defaultRepository.findByFactoryIdAndPurposeAndDeletedAtIsNull(F,
                WarehouseDefaultPurpose.SALES_OUTBOUND_DEFAULT)).get()
                .satisfies(row -> assertThat(row.getId()).isEqualTo("ADMIN-SALES"));
        assertThat(code(resolver::resolvePurchaseInboundWh)).isEqualTo("WH-RAW");
    }

    private String code(Function<String, String> route) {
        return warehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull(route.apply(F), F)
                .map(FactoryWarehouse::getCode).orElseThrow();
    }

    private static ApplyAIInitRequest request(ApplyAIInitRequest.WarehouseItem... items) {
        ApplyAIInitRequest.SuggestedData data = new ApplyAIInitRequest.SuggestedData();
        data.setWarehouses(List.of(items));
        ApplyAIInitRequest request = new ApplyAIInitRequest();
        request.setSuggestedData(data);
        return request;
    }

    private static ApplyAIInitRequest.WarehouseItem item(String code, String name, String type) {
        ApplyAIInitRequest.WarehouseItem item = new ApplyAIInitRequest.WarehouseItem();
        item.setCode(code);
        item.setName(name);
        item.setType(type);
        return item;
    }

    private static Check check(List<BusinessDomain> rows, String domain, String code) {
        return rows.stream().filter(row -> domain.equals(row.domain())).findFirst().orElseThrow()
                .checks().stream().filter(row -> code.equals(row.code())).findFirst().orElseThrow();
    }
}
