package com.cretas.aims.integration;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1606 独立审查 M4: 逐道录入 ({@code recordChain}) 的原料来源, 归属与领料同一口径
 * ({@code ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed})。真 PostgreSQL + 真 Hibernate + 真服务入口。
 *
 * <p>每条拒绝用例都配一个「一定会写」的读数: 同一夹具换成合规批次时会写消耗行 (阳性对照), 拒绝时消耗行 0 条。
 * 开关与库名约束同 {@link ClerkChainLineagePostgresIntegrationTest}。
 */
@SpringBootTest(classes = ClerkRawSourceOwnershipPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ClerkRawSourceOwnershipPostgresContractTest {

    static final String FACTORY = "M4-CLERK-PG";
    static final Long OPERATOR = 10058L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import(ClerkProcessEntryServiceImpl.class)
    static class Runtime { }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /** 计划级「插单占用」协作者, 与归属无关; 生产上它由完整上下文提供。 */
    @org.springframework.boot.test.mock.mockito.MockBean
    com.cretas.aims.service.production.SecondaryPlanFeedService secondaryPlanFeedService;

    @Autowired JdbcTemplate jdbc;
    @Autowired ClerkProcessEntryService clerk;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductTypeRepository productTypes;
    @Autowired MaterialBatchRepository materialBatches;

    @BeforeEach
    void setUp() {
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        if (!productTypes.existsById("M4-FIN")) {
            ProductType p = new ProductType();
            p.setId("M4-FIN");
            p.setFactoryId(FACTORY);
            p.setCode("M4-FIN");
            p.setName("成品");
            p.setCategory("FINISHED");
            p.setProductCategory("FINISHED");
            p.setUnit("kg");
            p.setIsActive(true);
            p.setCreatedBy(OPERATOR);
            productTypes.saveAndFlush(p);
            materialType("M4-MT-RAW", "原料");
            materialType("M4-MT-AUX", "辅料");
        }
    }

    private void materialType(String id, String mainCategory) {
        jdbc.update("insert into raw_material_types (id, factory_id, code, name, main_category, unit, is_byproduct, "
                + "self_supplied, tax_treatment, is_abaca_packaging, is_active, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, 'kg', false, false, 'TAXABLE', false, true, now(), now())",
                id, FACTORY, id, id, mainCategory);
    }

    private String plan(MaterialSupplyMode mode, String customerId, String salesOrderId) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-M4-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber(plan.getId());
        plan.setProductTypeId("M4-FIN");
        plan.setPlannedQuantity(new BigDecimal("10"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(OPERATOR);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        plan.setMaterialSupplyMode(mode);
        plan.setCustomerId(customerId);
        plan.setSourceOrderId(salesOrderId);
        plans.saveAndFlush(plan);
        return plan.getId();
    }

    private String rawBatch(String materialTypeId, InventoryOwnership ownership, String ownerCustomerId,
                            String salesOrderId) {
        MaterialBatch raw = new MaterialBatch();
        raw.setId("M4-RAW-" + UUID.randomUUID().toString().substring(0, 8));
        raw.setFactoryId(FACTORY);
        raw.setBatchNumber(raw.getId());
        raw.setMaterialTypeId(materialTypeId);
        raw.setWarehouseId("M4-WKS");
        raw.setReceiptQuantity(new BigDecimal("100"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(new BigDecimal("20"));
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setOwnership(ownership);
        raw.setOwnerCustomerId(ownerCustomerId);
        raw.setSourceSalesOrderId(salesOrderId);
        raw.setCreatedBy(OPERATOR);
        materialBatches.saveAndFlush(raw);
        return raw.getId();
    }

    private static ProcessChainEntryRequest oneStep(String rawBatchId) {
        RawInput ri = new RawInput();
        ri.setMaterialBatchId(rawBatchId);
        ri.setQuantity(new BigDecimal("10"));
        StepEntry s = new StepEntry();
        s.setProcessOrder(1);
        s.setProcessName("工序1");
        s.setInputQuantity(new BigDecimal("10"));
        s.setOutputQuantity(new BigDecimal("9"));
        s.setRawMaterialInputs(List.of(ri));
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey("FIN");
        b.setProductTypeId("M4-FIN");
        b.setFinished(true);
        b.setSteps(List.of(s));
        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey("M4-" + rawBatchId);
        req.setBatches(List.of(b));
        return req;
    }

    private int consumptionsOn(String rawBatchId) {
        return jdbc.queryForObject("select count(*) from material_consumptions where batch_id = ? and deleted_at is null",
                Integer.class, rawBatchId);
    }

    private void assertRefused(String planId, String rawBatchId, String code) {
        assertThatThrownBy(() -> clerk.recordChain(FACTORY, planId, oneStep(rawBatchId), OPERATOR))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
        assertThat(consumptionsOn(rawBatchId)).as("被拒的录入不留消耗行").isZero();
    }

    private void assertRecorded(String planId, String rawBatchId) {
        clerk.recordChain(FACTORY, planId, oneStep(rawBatchId), OPERATOR);
        assertThat(consumptionsOn(rawBatchId)).as("合规录入写消耗行").isEqualTo(1);
    }

    @Test
    void factoryStockPlan_refusesCustomerOwnedRaw_acceptsCompanyRaw() {
        String plan = plan(MaterialSupplyMode.FACTORY_SUPPLIED, null, null);
        assertRefused(plan, rawBatch("M4-MT-RAW", InventoryOwnership.CUSTOMER_OWNED, "CUST-A", "SO-A"),
                "CUSTOMER_OWNED_MATERIAL_FORBIDDEN");
        assertRecorded(plan, rawBatch("M4-MT-RAW", InventoryOwnership.COMPANY_OWNED, null, null));
    }

    @Test
    void customerSuppliedPlan_refusesOtherCustomersRaw_acceptsOwnCustomersRaw() {
        String plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "CUST-A", "SO-A");
        assertRefused(plan, rawBatch("M4-MT-RAW", InventoryOwnership.CUSTOMER_OWNED, "CUST-B", "SO-B"),
                "CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH");
        assertRefused(plan, rawBatch("M4-MT-RAW", InventoryOwnership.COMPANY_OWNED, null, null),
                "CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH");
        assertRecorded(plan, rawBatch("M4-MT-RAW", InventoryOwnership.CUSTOMER_OWNED, "CUST-A", "SO-A"));
    }

    /** 物料大类真的从物料字典读到了: 来料加工计划里, 公司自有的辅料照常放行 (大类读不到就会按原料拦下)。 */
    @Test
    void customerSuppliedPlan_acceptsCompanyAuxiliary() {
        String plan = plan(MaterialSupplyMode.CUSTOMER_SUPPLIED, "CUST-A", "SO-A");
        assertRecorded(plan, rawBatch("M4-MT-AUX", InventoryOwnership.COMPANY_OWNED, null, null));
    }
}
