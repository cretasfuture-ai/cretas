package com.cretas.aims.repository;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话列表工具的三条条件查询 (S6 验收 2026-11-03): 销售订单 / 质检记录 / 出货记录 按状态、日期 (及 DataScope /
 * 批次 / 类型 / 客户) 在<b>分页之前</b>过滤, 总数是筛过的。
 *
 * <p>默认跑 H2 (fast-contracts); {@code CRETAS_WORKFLOW_PG_VERIFY=true} 时跑真实 PostgreSQL 的一次性 schema
 * (java-gate-pr 的 PostgreSQL 作业) —— 可选条件为 null 时的类型绑定只有 PG 照得出来 (#1357)。
 * 每个查询都覆盖: 条件全空 / 条件全给 / 只给日期。
 */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ChatListFiltersRepositoryQueryValidationTest {

    private static final String F = "IT-CHAT-FILTER";
    private static final String OTHER = "IT-CHAT-FILTER-OTHER";
    private static final boolean PG = "true".equals(System.getenv("CRETAS_WORKFLOW_PG_VERIFY"));
    private static final String SCHEMA = "chat_filter_" + UUID.randomUUID().toString().replace("-", "");
    private static final PageRequest PAGE = PageRequest.of(0, 2);

    @Autowired private SalesOrderRepository sales;
    @Autowired private QualityInspectionRepository inspections;
    @Autowired private ShipmentRecordRepository shipments;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        if (PG) {
            pgExecute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (PG) {
            pgExecute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        if (!PG) {
            return;
        }
        String url = DisposablePostgresTargetGuard.requireSafeUrl(env("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> env("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @BeforeEach
    void seed() {
        if (PG) {
            // 只种这三张表, 不种外键目标: 删掉一次性 schema 里 Hibernate 建的外键 (owner 权限即可)
            jdbc.execute("""
                    DO $$ DECLARE r record; BEGIN
                      FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint
                               WHERE contype = 'f' AND conrelid::regclass::text IN
                                     ('sales_orders', 'quality_inspections', 'shipment_records') LOOP
                        EXECUTE 'ALTER TABLE ' || r.t || ' DROP CONSTRAINT ' || quote_ident(r.conname);
                      END LOOP;
                    END $$""");
        } else {
            jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        }
        jdbc.update("DELETE FROM sales_orders");
        jdbc.update("DELETE FROM quality_inspections");
        jdbc.update("DELETE FROM shipment_records");

        order("SO-1", F, LocalDate.of(2026, 10, 5), SalesOrderStatus.CONFIRMED, 7L);
        order("SO-2", F, LocalDate.of(2026, 10, 20), SalesOrderStatus.CANCELLED, 8L);
        order("SO-3", F, LocalDate.of(2026, 11, 1), SalesOrderStatus.CONFIRMED, 7L);
        order("SO-X", OTHER, LocalDate.of(2026, 10, 10), SalesOrderStatus.CONFIRMED, 7L);

        inspection("QI-1", F, 11L, LocalDate.of(2026, 11, 2), "PASS", "[质检类型: 原料检验] ok");
        inspection("QI-2", F, 12L, LocalDate.of(2026, 11, 1), "pass", null);
        inspection("QI-3", F, 11L, LocalDate.of(2026, 10, 15), "FAIL", null);
        inspection("QI-X", OTHER, 11L, LocalDate.of(2026, 11, 2), "FAIL", null);

        shipment("SH-1", F, "C1", LocalDate.of(2026, 10, 3), "shipped");
        shipment("SH-2", F, "C2", LocalDate.of(2026, 10, 25), "pending");
        shipment("SH-3", F, "C1", LocalDate.of(2026, 11, 2), "shipped");
        shipment("SH-X", OTHER, "C1", LocalDate.of(2026, 10, 3), "shipped");
    }

    // ------------------------------------------------------------------ 销售订单

    @Test
    void salesOrdersAllFiltersNull() {
        Page<SalesOrder> p = sales.searchFiltered(F, null, null, null, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(3);
        assertThat(p.getContent()).hasSize(2).extracting(SalesOrder::getFactoryId).containsOnly(F);
    }

    @Test
    void salesOrdersDatesOnlyFilterBeforePaging() {
        Page<SalesOrder> p = sales.searchFiltered(F, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);
        assertThat(p.getContent()).extracting(SalesOrder::getId).containsExactlyInAnyOrder("SO-1", "SO-2");
    }

    @Test
    void salesOrdersAllFiltersSet() {
        Page<SalesOrder> p = sales.searchFiltered(F, List.of(7L), SalesOrderStatus.CONFIRMED,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PAGE);
        assertThat(p.getContent()).extracting(SalesOrder::getId).containsExactly("SO-1");
        assertThat(p.getTotalElements()).isEqualTo(1);
    }

    @Test
    void salesOrdersScopedToAnEmptyChainSeeNothing() {
        assertThat(sales.searchFiltered(F, List.of(), null, null, null, PAGE).getTotalElements()).isZero();
    }

    // ------------------------------------------------------------------ 质检记录

    @Test
    void inspectionsAllFiltersNull() {
        Page<QualityInspection> p = inspections.searchFiltered(F, null, null, null, null, null, PAGE);
        assertThat(p.getTotalElements()).isEqualTo(3);
    }

    @Test
    void inspectionsResultFilterCountsFilteredRowsNotThePage() {
        // 以前「不合格」0 条却报「总计 6 条」: 总数必须是筛过的
        assertThat(inspections.searchFiltered(F, null, "CONDITIONAL", null, null, null, PAGE).getTotalElements())
                .isZero();
        assertThat(inspections.searchFiltered(F, null, "pass", null, null, null, PAGE).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void inspectionsDatesOnly() {
        Page<QualityInspection> p = inspections.searchFiltered(F, null, null,
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2), null, PAGE);
        assertThat(p.getContent()).extracting(QualityInspection::getId).containsExactly("QI-1");
    }

    @Test
    void inspectionsAllFiltersSet() {
        Page<QualityInspection> p = inspections.searchFiltered(F, 11L, "PASS",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "[质检类型: 原料检验]", PAGE);
        assertThat(p.getContent()).extracting(QualityInspection::getId).containsExactly("QI-1");
        assertThat(p.getTotalElements()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 出货记录

    @Test
    void shipmentsAllFiltersNull() {
        assertThat(shipments.searchFiltered(F, null, null, null, null, PAGE).getTotalElements()).isEqualTo(3);
    }

    @Test
    void shipmentsDatesOnly() {
        Page<ShipmentRecord> p = shipments.searchFiltered(F, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PAGE);
        assertThat(p.getTotalElements()).isEqualTo(2);
    }

    @Test
    void shipmentsAllFiltersSetTogether() {
        // 以前客户 / 日期 / 状态各走一条路径, 同时给就丢掉其余条件
        Page<ShipmentRecord> p = shipments.searchFiltered(F, "C1", "shipped",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PAGE);
        assertThat(p.getContent()).extracting(ShipmentRecord::getId).containsExactly("SH-1");
    }

    // ------------------------------------------------------------------ fixtures

    private void order(String id, String factoryId, LocalDate date, SalesOrderStatus status, long createdBy) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setFactoryId(factoryId);
        o.setOrderNumber("NO-" + id);
        o.setCustomerId("CU-1");
        o.setOrderDate(date);
        o.setStatus(status);
        o.setCreatedBy(createdBy);
        sales.saveAndFlush(o);
    }

    private void inspection(String id, String factoryId, long batch, LocalDate date, String result, String notes) {
        QualityInspection q = new QualityInspection();
        q.setId(id);
        q.setFactoryId(factoryId);
        q.setProductionBatchId(batch);
        q.setInspectorId(1L);
        q.setInspectionDate(date);
        q.setSampleSize(BigDecimal.TEN);
        q.setPassCount(BigDecimal.TEN);
        q.setFailCount(BigDecimal.ZERO);
        q.setResult(result);
        q.setNotes(notes);
        inspections.saveAndFlush(q);
    }

    private void shipment(String id, String factoryId, String customerId, LocalDate date, String status) {
        ShipmentRecord s = new ShipmentRecord();
        s.setId(id);
        s.setFactoryId(factoryId);
        s.setShipmentNumber("SN-" + id);
        s.setCustomerId(customerId);
        s.setProductName("P");
        s.setQuantity(BigDecimal.ONE);
        s.setUnit("kg");
        s.setShipmentDate(date);
        s.setStatus(status);
        s.setRecordedBy(1L);
        shipments.saveAndFlush(s);
    }

    private static void pgExecute(String sql) throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(env("CRETAS_WORKFLOW_PG_URL"));
        try (var c = DriverManager.getConnection(url, env("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return v;
    }
}
