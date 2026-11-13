package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.shortage.ShortageSupplyQueryRepository;
import com.cretas.aims.service.quality.release.MaterialBatchHoldService;
import com.cretas.aims.service.quality.release.QcReleaseService;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * #1590 遗留 M3 / M5 在真 PostgreSQL + 真 Hibernate + 真 Spring 事务上:
 * <ul>
 *   <li>M5: 冻品冻结 → 库里记下扣留前状态; 经「质检放行」放行 → 库里读回冻品 (不是一律可用), 放行台账写实际状态;
 *       冻结后判不合格 → 不良品, 扣留记录清空。</li>
 *   <li>M3: 缺料「质检中供应」真查询不数被扣留的批次 (新列 + 老数据审计标记), 首检中的批次照数 (阳性对照)。</li>
 *   <li>并发: 两个冻结同时打同一批, 一个扣成功, 另一个在锁后重读、如实回「已扣住」, 不抛乐观锁、不把原状态记成质检中。</li>
 *   <li>迁移 V20261113_91: 加列幂等; 扣留前状态只在质检中时允许、只收能用的状态。</li>
 * </ul>
 * 由 java-gate 的 postgres 分片执行 (清单 java-gate-pg-tests.txt); 本机用一次性 initdb 集群跑。
 */
@SpringBootTest(classes = MaterialBatchHoldPostgresRepositoryQueryValidationTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class MaterialBatchHoldPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "QA-HOLD-PG";
    private static final Long USER_ID = 7105L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({QcReleaseService.class, MaterialBatchHoldService.class,
            com.cretas.aims.service.discipline.MaterialBatchReleaseGuard.class,
            com.cretas.aims.service.discipline.FactoryDisciplinePolicy.class,
            com.cretas.aims.service.canvas.impl.ThresholdResolverServiceImpl.class})
    static class Runtime {
    }

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

    @Autowired MaterialBatchHoldService holds;
    @Autowired QcReleaseService releases;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired RawMaterialTypeRepository rawTypes;
    @Autowired ShortageSupplyQueryRepository supply;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
    }

    @Test
    void frozenGoodsHeldThenReleasedComeBackAsFrozenAndHeldStockIsNotCountedAsSupply() {
        String token = suffix();
        String typeId = saveRawType("RMT-H-" + token);
        String held = saveRawBatch(typeId, "QA-H-" + token, MaterialBatchStatus.FROZEN, "50", "0", "0");
        String firstInspection = saveRawBatch(typeId, "QA-F-" + token, MaterialBatchStatus.INSPECTING, "30", "0", "0");
        // 阳性对照: 首检中的 30 照数
        assertThat(supply.sumInspectingQuantity(FACTORY, typeId)).isEqualByComparingTo("30");

        MaterialBatchHoldService.FreezeResult r = holds.freeze(FACTORY, "QA-H-" + token, "QA 召回演练", USER_ID);

        assertThat(r.held()).isTrue();
        assertThat(statusOf(held)).isEqualTo("INSPECTING");
        assertThat(priorOf(held)).isEqualTo("FROZEN");
        // M3: 被扣留的 50 不算正在到来的供应
        assertThat(supply.sumInspectingQuantity(FACTORY, typeId)).isEqualByComparingTo("30");

        releases.decide(FACTORY, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, held, "RELEASED", "排查无问题"), USER_ID, "质检员");

        // M5: 放行回冻品, 扣留记录清空; 放行台账写的是实际写入的状态
        assertThat(statusOf(held)).isEqualTo("FROZEN");
        assertThat(priorOf(held)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from material_batch_releases where factory_id = ?"
                        + " and material_batch_id = ? and from_status = 'INSPECTING' and to_status = 'FROZEN'",
                Long.class, FACTORY, held)).isEqualTo(1L);
        assertThat(statusOf(firstInspection)).isEqualTo("INSPECTING");
    }

    @Test
    void fullyReservedBatchHeldThenReleasedComesBackDepletedNotAvailable() {
        String token = suffix();
        String typeId = saveRawType("RMT-D-" + token);
        String id = saveRawBatch(typeId, "QA-D-" + token, MaterialBatchStatus.DEPLETED, "50", "0", "50");

        assertThat(holds.freeze(FACTORY, "QA-D-" + token, "QA 召回演练", USER_ID).held()).isTrue();
        assertThat(priorOf(id)).isEqualTo("DEPLETED");
        releases.decide(FACTORY, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, id, "RELEASED", "排查无问题"), USER_ID, "质检员");
        assertThat(statusOf(id)).isEqualTo("DEPLETED");
    }

    @Test
    void rejectionAfterHoldClearsPriorAndStaysDefective() {
        String token = suffix();
        String typeId = saveRawType("RMT-J-" + token);
        String id = saveRawBatch(typeId, "QA-J-" + token, MaterialBatchStatus.FRESH, "50", "0", "0");
        holds.freeze(FACTORY, "QA-J-" + token, "QA 召回演练", USER_ID);

        releases.decide(FACTORY, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, id, "REJECTED", "检出异物"), USER_ID, "质检员");
        assertThat(statusOf(id)).isEqualTo("DEFECTIVE");
        assertThat(priorOf(id)).isNull();
    }

    @Test
    void legacyFrozenBatchWithAuditMarkOnlyIsNotCountedAsSupply() {
        String token = suffix();
        String typeId = saveRawType("RMT-L-" + token);
        String id = saveRawBatch(typeId, "QA-L-" + token, MaterialBatchStatus.INSPECTING, "40", "0", "0");
        assertThat(supply.sumInspectingQuantity(FACTORY, typeId)).isEqualByComparingTo("40");   // 阳性对照
        jdbc.update("update material_batches set notes = ? where id = ?",
                "[2026-11-12T10:00] " + MaterialBatchHoldService.FREEZE_AUDIT_MARK + "1] 召回 (prev=AVAILABLE)", id);
        assertThat(supply.sumInspectingQuantity(FACTORY, typeId)).isEqualByComparingTo("0");
    }

    @Test
    void reservationsThatEndedDuringTheHoldAreNotRestoredOnRelease() {
        // #1689 终审 B1: 扣留期间工序完工 / 预留取消, 放行不能还原成已不成立的生产预留 / 已预留
        String token = suffix();
        String typeId = saveRawType("RMT-W-" + token);
        String wip = saveRawBatch(typeId, "QA-W-" + token, MaterialBatchStatus.PRODUCING_RESERVED, "50", "20", "0");
        String res = saveRawBatch(typeId, "QA-R-" + token, MaterialBatchStatus.RESERVED, "50", "0", "10");
        String keep = saveRawBatch(typeId, "QA-K-" + token, MaterialBatchStatus.RESERVED, "50", "0", "10");
        for (String n : new String[]{"QA-W-", "QA-R-", "QA-K-"}) {
            assertThat(holds.freeze(FACTORY, n + token, "QA 召回演练", USER_ID).held()).isTrue();
        }
        tx.executeWithoutResult(s -> {
            MaterialBatch w = materialBatches.findById(wip).orElseThrow();
            assertThat(MaterialBatchHoldService.followWip(w, false)).isTrue();   // 工序完工 (在制释放入口同一调用)
            materialBatches.save(w);
            MaterialBatch r = materialBatches.findById(res).orElseThrow();
            r.setReservedQuantity(java.math.BigDecimal.ZERO);                      // 预留取消
            materialBatches.save(r);
        });
        assertThat(priorOf(wip)).isEqualTo("AVAILABLE");
        for (String id : new String[]{wip, res, keep}) {
            releases.decide(FACTORY, new QcReleaseService.DecisionCommand(
                    QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, id, "RELEASED", "排查无问题"), USER_ID, "质检员");
        }
        assertThat(statusOf(wip)).isEqualTo("AVAILABLE");
        assertThat(statusOf(res)).isEqualTo("AVAILABLE");
        assertThat(statusOf(keep)).isEqualTo("RESERVED");   // 阳性对照: 预留仍在 → 回已预留
    }

    @Test
    void holdWithoutAuditNoteIsNotCountedAsSupply() {
        // CCP 偏离扣留 / 冷链转待检 / 仓库页改质检中 走 hold(), 不写冻结审计备注, 只靠 hold_prior_status 识别
        String token = suffix();
        String typeId = saveRawType("RMT-N-" + token);
        String id = saveRawBatch(typeId, "QA-N-" + token, MaterialBatchStatus.AVAILABLE, "40", "0", "0");
        tx.executeWithoutResult(s -> {
            MaterialBatch b = materialBatches.findById(id).orElseThrow();
            MaterialBatchHoldService.hold(b);
            materialBatches.save(b);
        });
        assertThat(statusOf(id)).isEqualTo("INSPECTING");
        assertThat(priorOf(id)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select coalesce(notes,'') from material_batches where id = ?", String.class, id))
                .doesNotContain(MaterialBatchHoldService.FREEZE_AUDIT_MARK);
        assertThat(supply.sumInspectingQuantity(FACTORY, typeId)).isEqualByComparingTo("0");
        // 阳性对照: 同一批清掉扣留记录 = 首检中, 照数
        jdbc.update("update material_batches set hold_prior_status = null where id = ?", id);
        assertThat(supply.sumInspectingQuantity(FACTORY, typeId)).isEqualByComparingTo("40");
    }

    @Test
    void concurrentFreezesHoldOnceAndSecondSeesItAlreadyHeld() throws Exception {
        String token = suffix();
        String typeId = saveRawType("RMT-C-" + token);
        String id = saveRawBatch(typeId, "QA-C-" + token, MaterialBatchStatus.FROZEN, "50", "0", "0");
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            boolean held = holds.freeze(FACTORY, "QA-C-" + token, "第一个", USER_ID).held();
            firstHolds.countDown();
            await(secondStarted);
            sleep(800);   // 第二个此时应卡在行锁上
            return held;
        }));
        assertThat(firstHolds.await(20, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MaterialBatchHoldService.FreezeResult> second = CompletableFuture.supplyAsync(() -> {
            secondStarted.countDown();
            return holds.freeze(FACTORY, "QA-C-" + token, "第二个", USER_ID);
        });

        assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
        MaterialBatchHoldService.FreezeResult r2 = second.get(30, TimeUnit.SECONDS);
        System.out.println("HOLD_PG_CONCURRENT second.held=" + r2.held() + " prev=" + r2.previousStatus()
                + " prior=" + priorOf(id));
        assertThat(r2.held()).isFalse();
        assertThat(r2.previousStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        assertThat(priorOf(id)).isEqualTo("FROZEN");
    }

    @Test
    void migrationAddsColumnIdempotentlyAndConstrainsPriorToInspectingUsableStates() throws Exception {
        String schema = "mb_hold_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String sql = new ClassPathResource("db/flyway/V20261113_91__material_batch_hold_prior_status.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
            st.execute("SET search_path TO " + schema);
            st.execute("CREATE TABLE material_batches (id VARCHAR(191) PRIMARY KEY, status VARCHAR(32) NOT NULL)");
            st.execute("INSERT INTO material_batches VALUES ('old-1', 'AVAILABLE')");   // 存量行不受影响
            st.execute(sql);
            st.execute(sql);   // 重放不炸

            assertDoesNotThrow(() -> st.execute("INSERT INTO material_batches VALUES ('h-1', 'INSPECTING', 'FROZEN')"));
            assertDoesNotThrow(() -> st.execute("INSERT INTO material_batches VALUES ('h-2', 'INSPECTING', NULL)"));
            assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO material_batches VALUES ('h-3', 'AVAILABLE', 'FROZEN')"),
                    "不在质检中却带扣留前状态");
            assertThrows(SQLException.class,
                    () -> st.execute("INSERT INTO material_batches VALUES ('h-4', 'INSPECTING', 'DEFECTIVE')"),
                    "不良品不能当扣留前状态 (否则放行会还原成不良品 / 或借扣留洗白)");
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    // ── helpers ──

    private String statusOf(String id) {
        return jdbc.queryForObject("select status from material_batches where id = ?", String.class, id);
    }

    private String priorOf(String id) {
        return jdbc.queryForObject("select hold_prior_status from material_batches where id = ?", String.class, id);
    }

    private String saveRawType(String id) {
        RawMaterialType t = new RawMaterialType();
        t.setId(id);
        t.setFactoryId(FACTORY);
        t.setCode(id);
        t.setName("QA-冻猪蹄-" + id);
        t.setUnit("kg");
        rawTypes.saveAndFlush(t);
        return id;
    }

    private String saveRawBatch(String typeId, String batchNumber, MaterialBatchStatus status,
                                String receipt, String used, String reserved) {
        MaterialBatch b = new MaterialBatch();
        String id = "MB-" + batchNumber;
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setBatchNumber(batchNumber);
        b.setMaterialTypeId(typeId);
        b.setReceiptDate(LocalDate.now());
        b.setWarehouseId("WH-LOG");
        b.setReceiptQuantity(new BigDecimal(receipt));
        b.setQuantityUnit("kg");
        b.setUsedQuantity(new BigDecimal(used));
        b.setReservedQuantity(new BigDecimal(reserved));
        b.setStatus(status);
        b.setSourceDocType("PURCHASE_RECEIVE");
        b.setCreatedBy(USER_ID);
        materialBatches.saveAndFlush(b);
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
