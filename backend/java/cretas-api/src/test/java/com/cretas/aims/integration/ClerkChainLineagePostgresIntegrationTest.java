package com.cretas.aims.integration;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.RawInput;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.UpstreamSource;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.lineage.FinishedBatchProductionAligner;
import com.cretas.aims.service.lineage.LineageBackfillService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageEdgeWriter;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 PostgreSQL：文员逐道录入 3 道工序 → 终端批次的闭包祖先含首道原料。
 *
 * <p>为什么一定要真库：「能追到哪」是 {@code batch_lineage_closure}（触发器 {@code fn_maintain_lineage_closure}
 * 维护）回答的，不是边表。H2 没有这个触发器；单测里自己做 BFS 证明的是「边连通」，
 * 这里证明的是「线上那张闭包表真的给出了 depth=5 的祖先」—— prod 实测 10 道工序的批只到 depth 1。
 *
 * <p>schema：实体由 Hibernate create-drop 建，两张血缘表<b>换成迁移原文</b>（含触发器与 uq_ble_refkey），
 * 外键全部摘掉（一次性库，只为不铺整棵依赖树）。业务写入走真实 {@code recordChain}（自带事务，
 * 本测试不包事务 ⇒ afterCommit 真的触发、边真的落库）。
 *
 * <p>开关：{@code CRETAS_WORKFLOW_PG_VERIFY=true} + {@code CRETAS_WORKFLOW_PG_URL}（库名必须
 * {@code cretas_workflow_verify_} 前缀，见 {@link DisposablePostgresTargetGuard}）。
 * 本机跑法：{@code scripts/ci/run-with-test-postgres.sh} 同款一次性实例。
 */
@SpringBootTest(classes = ClerkChainLineagePostgresIntegrationTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        // 与 prod 同一个驱动口径 (application-pg-prod.properties 的 URL 带 stringtype=unspecified):
        // 若干 String 字段映射到 jsonb 列, 不加这个 PG 会拒绝 varchar→jsonb。URL 守卫不许带 query, 走数据源属性。
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class ClerkChainLineagePostgresIntegrationTest {

    static final String FACTORY = "LINEAGE-PG";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({ClerkProcessEntryServiceImpl.class, LineageEdgeRecorder.class, LineageEdgeWriter.class,
            FinishedBatchProductionAligner.class, LineageBackfillService.class})
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

    @Autowired JdbcTemplate jdbc;
    @Autowired ClerkProcessEntryService clerk;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductTypeRepository productTypes;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired LineageBackfillService backfill;

    @Test
    void threeStepClerkChainFinishedBatchReachesFirstStepRawMaterialInClosure() throws Exception {
        installLineageSchemaFromMigrations();
        dropAllForeignKeys();
        seed();

        ProcessChainEntryResult result = clerk.recordChain(FACTORY, "PLAN-LPG", threeStepChain(), 10058L);
        Long finished = result.getBatchIdsByKey().get("FIN");
        assertThat(finished).isNotNull();

        // 「一定会发生」的读数: 两个中间产出各一条 PRODUCTION_TO_WIP 边真的落库了 (afterCommit 真的跑了)。
        Integer wipEdges = jdbc.queryForObject(
                "select count(*) from batch_lineage_edges where factory_id = ? and edge_type = 'PRODUCTION_TO_WIP'",
                Integer.class, FACTORY);
        Integer allEdges = jdbc.queryForObject(
                "select count(*) from batch_lineage_edges where factory_id = ?", Integer.class, FACTORY);
        System.out.println("[lineage-pg] edges=" + allEdges + " PRODUCTION_TO_WIP=" + wipEdges
                + " finishedBatch=" + finished);
        assertThat(wipEdges).isEqualTo(2);

        List<Map<String, Object>> ancestors = jdbc.queryForList(
                "select ancestor_type, ancestor_id, depth from batch_lineage_closure "
                        + "where factory_id = ? and descendant_type = 'PRODUCTION_BATCH' and descendant_id = ? "
                        + "and depth > 0 order by depth", FACTORY, String.valueOf(finished));
        System.out.println("[lineage-pg] closure ancestors of finished batch = " + ancestors);
        assertThat(ancestors)
                .as("终端批的闭包祖先必须含首道原料 (prod 实测只到 depth 1)")
                .anySatisfy(row -> {
                    assertThat(row.get("ancestor_type")).isEqualTo("MATERIAL_BATCH");
                    assertThat(row.get("ancestor_id")).isEqualTo("LPG-RAW-1");
                    assertThat(((Number) row.get("depth")).intValue()).isEqualTo(5);
                });

        // 回填预演跑在真 Hibernate 上 (JPQL 与实体字段对得上), 且与在线同一个幂等键:
        // 在线刚写的两条 PRODUCTION_TO_WIP, 预演必须报「已存在 2 / 新增 0」, 否则部署后回填会双写。
        LineageBackfillService.Report report = backfill.backfillFactory(FACTORY, true);
        System.out.println("[lineage-pg] " + report.render());
        assertThat(report.productionToWipStock().getAlreadyExists()).isEqualTo(2);
        assertThat(report.productionToWipStock().getInserted()).isZero();
        assertThat(report.rawToProduction().getAlreadyExists()).isEqualTo(3);
        assertThat(report.rawToProduction().getInserted()).isZero();
    }

    private void installLineageSchemaFromMigrations() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS batch_lineage_closure CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS batch_lineage_edges CASCADE");
        jdbc.execute(migration("db/flyway/V20260821_02__batch_lineage_schema.sql"));
        jdbc.execute(migration("db/flyway/V20261101_43__batch_lineage_edges_refkey_unique.sql"));
    }

    private void dropAllForeignKeys() {
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    private static String migration(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void seed() {
        for (String[] pt : new String[][]{{"LPG-SEMI-1", "半成品1"}, {"LPG-SEMI-2", "半成品2"}, {"LPG-FIN", "成品"}}) {
            ProductType p = new ProductType();
            p.setId(pt[0]);
            p.setFactoryId(FACTORY);
            p.setCode(pt[0]);
            p.setName(pt[1]);
            p.setCategory("SEMI_FINISHED");
            p.setProductCategory("SEMI_FINISHED");
            p.setUnit("kg");
            p.setIsActive(true);
            p.setCreatedBy(10058L);
            productTypes.saveAndFlush(p);
        }
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-LPG");
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("LPG-PLAN-1");
        plan.setProductTypeId("LPG-FIN");
        plan.setPlannedQuantity(new BigDecimal("6"));
        plan.setPlannedUnit("kg");
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setCreatedBy(10058L);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        plans.saveAndFlush(plan);

        MaterialBatch raw = new MaterialBatch();
        raw.setId("LPG-RAW-1");
        raw.setFactoryId(FACTORY);
        raw.setBatchNumber("LPG-RAW-BATCH-1");
        raw.setMaterialTypeId("LPG-MT-PORK");
        raw.setWarehouseId("LPG-WH");
        raw.setReceiptQuantity(new BigDecimal("100"));
        raw.setQuantityUnit("kg");
        raw.setUsedQuantity(BigDecimal.ZERO);
        raw.setReservedQuantity(BigDecimal.ZERO);
        raw.setUnitPrice(new BigDecimal("20"));
        raw.setStatus(MaterialBatchStatus.AVAILABLE);
        raw.setReceiptDate(LocalDate.now());
        raw.setCreatedBy(10058L);
        materialBatches.saveAndFlush(raw);
    }

    private static ProcessChainEntryRequest threeStepChain() {
        RawInput ri = new RawInput();
        ri.setMaterialBatchId("LPG-RAW-1");
        ri.setQuantity(new BigDecimal("10"));
        StepEntry s1 = step(1, "10", "8");
        s1.setRawMaterialInputs(List.of(ri));
        StepEntry s2 = step(2, "8", "7");
        s2.setUpstreamSources(List.of(upstream("W1", "8")));
        StepEntry s3 = step(3, "7", "6");
        s3.setUpstreamSources(List.of(upstream("W2", "7")));
        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey("LPG-CHAIN-1");
        req.setBatches(List.of(batch("W1", "LPG-SEMI-1", false, s1), batch("W2", "LPG-SEMI-2", false, s2),
                batch("FIN", "LPG-FIN", true, s3)));
        return req;
    }

    private static StepEntry step(int order, String in, String out) {
        StepEntry s = new StepEntry();
        s.setProcessOrder(order);
        s.setProcessName("工序" + order);
        s.setInputQuantity(new BigDecimal(in));
        s.setOutputQuantity(new BigDecimal(out));
        return s;
    }

    private static UpstreamSource upstream(String key, String kg) {
        UpstreamSource u = new UpstreamSource();
        u.setSourceClientBatchKey(key);
        u.setFeedQuantityKg(new BigDecimal(kg));
        return u;
    }

    private static BatchEntry batch(String key, String productTypeId, boolean finished, StepEntry step) {
        BatchEntry b = new BatchEntry();
        b.setClientBatchKey(key);
        b.setProductTypeId(productTypeId);
        b.setFinished(finished);
        b.setSteps(List.of(step));
        return b;
    }
}
