package com.cretas.aims.integration;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
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
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D-S411-04 在真 PostgreSQL + 真 Hibernate + 真 Spring 事务上:
 * <ul>
 *   <li>质量角色经「质检放行」放行原料收货批次 → 库里读回 AVAILABLE, 放行记录落库, 事务提交之后
 *       {@link MaterialBatchReleasedEvent} 送到一个与 {@code ShortageCaseListener} 同形的
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} 监听器 (缺料实例就是这样收到的);</li>
 *   <li>别厂批次: 列不出来, 放行 404, 库里一行不动;</li>
 *   <li>迁移 V20261110_31: qc_release_records.target_type 收 RAW_MATERIAL_BATCH, 仍拒绝未知值。</li>
 * </ul>
 * 本机禁 Docker/WSL 跑不了, 由 java-gate 的 postgres-reversal-concurrency 分片执行 (清单 java-gate-pg-tests.txt)。
 */
@SpringBootTest(classes = QcReleaseRawMaterialPostgresContractTest.Runtime.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoint.health.validate-group-membership=false",
        "spring.datasource.hikari.data-source-properties.stringtype=unspecified",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class QcReleaseRawMaterialPostgresContractTest {

    private static final String FACTORY = "D411-QC-PG";
    private static final String OTHER_FACTORY = "D411-QC-OTHER";
    private static final Long USER_ID = 7104L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @EntityScan("com.cretas.aims.entity")
    @EnableJpaRepositories("com.cretas.aims.repository")
    @Import({QcReleaseService.class, ReleasedEvents.class})
    static class Runtime {
    }

    /** 与 ShortageCaseListener.onMaterialBatchReleased 同一种监听: 事务提交之后才收到。 */
    @Component
    static class ReleasedEvents {
        final List<MaterialBatchReleasedEvent> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        public void on(MaterialBatchReleasedEvent event) {
            received.add(event);
        }
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

    @Autowired QcReleaseService releases;
    @Autowired ReleasedEvents events;
    @Autowired MaterialBatchRepository materialBatches;
    @Autowired RawMaterialTypeRepository rawTypes;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Disposable target with entity-generated DDL and no production FK fixtures (same as the sibling PG tests).
        jdbc.execute("DO $$ DECLARE r record; BEGIN "
                + "FOR r IN SELECT conrelid::regclass AS t, conname FROM pg_constraint "
                + "WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP "
                + "EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.t, r.conname); END LOOP; END $$");
        events.received.clear();
    }

    @Test
    void qualityReleaseOfInspectingRawBatchCommitsAvailableAndNotifiesShortageListener() {
        String token = suffix();
        String typeId = saveRawType(FACTORY, "RMT-" + token, "冻猪蹄-" + token);
        String batchId = saveRawBatch(FACTORY, typeId, "MT-" + token, MaterialBatchStatus.INSPECTING);

        List<QcReleaseService.PendingItem> pending = releases.listPending(FACTORY);
        assertThat(pending).filteredOn(p -> batchId.equals(p.targetId())).singleElement().satisfies(p -> {
            assertThat(p.targetType()).isEqualTo(QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH);
            assertThat(p.productName()).isEqualTo("冻猪蹄-" + token);
            assertThat(p.unit()).isEqualTo("kg");
            assertThat(p.quantity()).isEqualByComparingTo("50");
        });

        QcReleaseRecord saved = releases.decide(FACTORY, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, batchId, "RELEASED", "感官检验合格"), USER_ID, "质检员");

        assertThat(jdbc.queryForObject("select status from material_batches where id = ?", String.class, batchId))
                .isEqualTo(MaterialBatchStatus.AVAILABLE.name());
        assertThat(jdbc.queryForObject("select count(*) from qc_release_records where id = ? and factory_id = ?"
                        + " and target_type = 'RAW_MATERIAL_BATCH' and target_id = ? and decision = 'RELEASED'",
                Long.class, saved.getId(), FACTORY, batchId)).isEqualTo(1L);
        assertThat(events.received).singleElement().satisfies(e -> {
            assertThat(e.getFactoryId()).isEqualTo(FACTORY);
            assertThat(e.getMaterialTypeId()).isEqualTo(typeId);
            assertThat(e.getMaterialBatchId()).isEqualTo(batchId);
        });
        assertThat(releases.listPending(FACTORY)).noneMatch(p -> batchId.equals(p.targetId()));
    }

    @Test
    void otherFactoryRawBatchIsInvisibleAndCannotBeReleased() {
        String token = suffix();
        String typeId = saveRawType(OTHER_FACTORY, "RMT-X-" + token, "别厂原料-" + token);
        String foreign = saveRawBatch(OTHER_FACTORY, typeId, "MT-X-" + token, MaterialBatchStatus.INSPECTING);

        assertThat(releases.listPending(FACTORY)).noneMatch(p -> foreign.equals(p.targetId()));
        assertThatThrownBy(() -> releases.decide(FACTORY, new QcReleaseService.DecisionCommand(
                QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, foreign, "RELEASED", "感官检验合格"), USER_ID, "质检员"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));

        assertThat(jdbc.queryForObject("select status from material_batches where id = ?", String.class, foreign))
                .isEqualTo(MaterialBatchStatus.INSPECTING.name());
        assertThat(jdbc.queryForObject("select count(*) from qc_release_records where target_id = ?",
                Long.class, foreign)).isZero();
        assertThat(events.received).isEmpty();
        // 阳性对照: 同一批在它自己的厂里列得出来
        assertThat(releases.listPending(OTHER_FACTORY)).anyMatch(p -> foreign.equals(p.targetId()));
    }

    @Test
    void migrationAcceptsRawMaterialTargetAndStillRejectsUnknown() throws Exception {
        String schema = "qc_release_raw_" + UUID.randomUUID().toString().replace("-", "");
        String url = DisposablePostgresTargetGuard.requireSafeUrl(System.getenv("CRETAS_WORKFLOW_PG_URL"));
        String user = System.getenv("CRETAS_WORKFLOW_PG_USER");
        String password = System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", "");
        String insert = "INSERT INTO qc_release_records (factory_id, target_type, target_id, decision, conclusion,"
                + " released_by, released_at) VALUES ('F1', '%s', 'MB-1', 'RELEASED', 'ok', 1, now())";
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            // V20261103_88 还改 product_types / semi_finished_inventory: 只给它够用的两张壳表
            statement.execute("CREATE TABLE product_types (id VARCHAR(191) PRIMARY KEY)");
            statement.execute("CREATE TABLE semi_finished_inventory (id BIGSERIAL PRIMARY KEY, factory_id VARCHAR(64),"
                    + " deleted_at TIMESTAMP)");
            // 整个脚本交给 pgjdbc 执行 (它认得 $$ 引号; ScriptUtils 按分号切会把 DO 块切碎)
            statement.execute(migration("V20261103_88__qc_release_inspection_hold.sql"));
            // 阴性对照: 迁移之前原料目标被拒 —— 证明下面那条「收了」是迁移带来的
            assertThrows(SQLException.class, () -> statement.execute(String.format(insert, "RAW_MATERIAL_BATCH")),
                    "before V20261110_31 the inline CHECK rejects RAW_MATERIAL_BATCH");

            String raw = migration("V20261110_31__qc_release_raw_material_target.sql");
            statement.execute(raw);
            // 重放一次 (Flyway repair / 手工重跑) 也不炸
            statement.execute(raw);

            for (String t : List.of("RAW_MATERIAL_BATCH", "SEMI_FINISHED", "WIP_MATERIAL_BATCH", "FINISHED_GOODS")) {
                assertDoesNotThrow(() -> statement.execute(String.format(insert, t)), t);
            }
            assertThrows(SQLException.class, () -> statement.execute(String.format(insert, "GUESS")),
                    "unknown target type");
        } finally {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String migration(String file) throws java.io.IOException {
        return new ClassPathResource("db/flyway/" + file).getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String saveRawType(String factoryId, String id, String name) {
        RawMaterialType t = new RawMaterialType();
        t.setId(id);
        t.setFactoryId(factoryId);
        t.setCode(id);
        t.setName(name);
        t.setUnit("kg");
        rawTypes.saveAndFlush(t);
        return id;
    }

    private String saveRawBatch(String factoryId, String typeId, String batchNumber, MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        String id = "MB-" + batchNumber;
        b.setId(id);
        b.setFactoryId(factoryId);
        b.setBatchNumber(batchNumber);
        b.setMaterialTypeId(typeId);
        b.setReceiptDate(LocalDate.now());
        b.setWarehouseId("WH-LOG");
        b.setReceiptQuantity(new BigDecimal("50"));
        b.setQuantityUnit("kg");
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(status);
        b.setSourceDocType("PURCHASE_RECEIVE");
        b.setCreatedBy(USER_ID);
        materialBatches.saveAndFlush(b);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
