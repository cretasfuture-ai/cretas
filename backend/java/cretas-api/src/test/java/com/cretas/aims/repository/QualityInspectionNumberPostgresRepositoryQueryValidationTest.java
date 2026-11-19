package com.cretas.aims.repository;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL proof for the quality inspection business number (V20261119_86, PROTO-QC-NUMBER).
 *
 * <p>Applies the <b>actual</b> migration through Flyway onto a Hibernate-created schema, then writes through the real
 * Spring Data repository / Hibernate mapping ({@code @Generated(INSERT)} read-back). Covers: legacy backfill
 * (per factory per day, soft-deleted rows numbered, updated_at untouched), new rows continue after the soft-deleted
 * maximum (deleted numbers are never reissued), two inserts in one transaction, two concurrent transactions
 * (advisory lock serializes; without it the second one hits the unique index), and keyword search by number.</p>
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityInspectionNumberPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-QI-NO-PG";
    private static final String OTHER_FACTORY = "IT-QI-NO-PG-OTHER";
    private static final String SCHEMA = "qi_no_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired private QualityInspectionRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
        }
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> requiredEnv("CRETAS_WORKFLOW_PG_USER"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO \"" + SCHEMA + "\"");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void resetToPreMigrationState() {
        // 只造质检记录, 不造外键目标: 摘掉 Hibernate 在这个一次性 schema 里生成的外键
        jdbc.execute("""
                DO $$ DECLARE r record; BEGIN
                  FOR r IN SELECT conname FROM pg_constraint
                           WHERE conrelid = 'quality_inspections'::regclass AND contype = 'f' LOOP
                    EXECUTE 'ALTER TABLE quality_inspections DROP CONSTRAINT ' || quote_ident(r.conname);
                  END LOOP;
                END $$""");
        // 回到「迁移前」: 无发号触发器、无唯一索引、无迁移记录 (列由 Hibernate create 建好, 迁移里 IF NOT EXISTS)
        jdbc.execute("DROP TRIGGER IF EXISTS trg_quality_inspection_number ON quality_inspections");
        jdbc.execute("DROP INDEX IF EXISTS uk_quality_inspections_factory_number");
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
        jdbc.update("DELETE FROM quality_inspections");
        // prod 上存在的 updated_at 触发器 (migration-pg/V0002): 回填不许把它触发成「全部今天更新」
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION update_timestamp() RETURNS TRIGGER AS $$
                BEGIN NEW.updated_at = CURRENT_TIMESTAMP; RETURN NEW; END; $$ LANGUAGE plpgsql""");
        jdbc.execute("DROP TRIGGER IF EXISTS quality_inspections_update_timestamp ON quality_inspections");
        jdbc.execute("CREATE TRIGGER quality_inspections_update_timestamp BEFORE UPDATE ON quality_inspections "
                + "FOR EACH ROW EXECUTE FUNCTION update_timestamp()");
    }

    @Test
    void migrationBackfillsLegacyRowsPerFactoryPerDayCountingSoftDeletedAndKeepsUpdatedAt() {
        LocalDateTime d1 = LocalDateTime.of(2026, 11, 10, 8, 0);
        LocalDateTime old = LocalDateTime.of(2026, 11, 11, 7, 0);
        legacyRow("L-3", FACTORY, d1.plusHours(2), null, old);
        legacyRow("L-1", FACTORY, d1, null, old);
        legacyRow("L-2", FACTORY, d1.plusHours(1), d1.plusHours(5), old);   // 已软删, 仍占号
        legacyRow("L-4", FACTORY, d1.plusDays(1), null, old);
        legacyRow("L-5", OTHER_FACTORY, d1, null, old);

        migrate();

        assertThat(number("L-1")).isEqualTo("QI-20261110-0001");
        assertThat(number("L-2")).isEqualTo("QI-20261110-0002");
        assertThat(number("L-3")).isEqualTo("QI-20261110-0003");
        assertThat(number("L-4")).isEqualTo("QI-20261111-0001");
        assertThat(number("L-5")).isEqualTo("QI-20261110-0001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quality_inspections WHERE updated_at <> ?",
                Integer.class, Timestamp.valueOf(old))).as("回填不能刷新 updated_at").isZero();
        assertThat(jdbc.queryForObject("SELECT tgenabled FROM pg_trigger WHERE tgname = 'quality_inspections_update_timestamp'",
                String.class)).as("updated_at 触发器回填后要恢复启用").isEqualTo("O");
    }

    @Test
    void newRowThroughJpaContinuesAfterSoftDeletedMaximumAndReadsBackTheNumber() {
        migrate();
        String prefix = "QI-" + LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-";
        // 今天已发到 0007 的那条被软删了: 新记录必须拿 0008, 不许把 0001..0007 再发出去
        jdbc.update("INSERT INTO quality_inspections (id, inspection_number, factory_id, production_batch_id, inspector_id, "
                        + "inspection_date, sample_size, pass_count, fail_count, result, created_at, updated_at, deleted_at) "
                        + "VALUES ('DEL-7', ?, ?, 1, 1, CURRENT_DATE, 1, 1, 0, 'PASS', NOW(), NOW(), NOW())",
                prefix + "0007", FACTORY);

        QualityInspection saved = new TransactionTemplate(transactionManager)
                .execute(status -> repository.saveAndFlush(inspection(FACTORY)));

        String expectedPrefix = "QI-" + jdbc.queryForObject(
                "SELECT to_char(created_at, 'YYYYMMDD') FROM quality_inspections WHERE id = ?", String.class, saved.getId()) + "-";
        assertThat(expectedPrefix).isEqualTo(prefix);
        assertThat(saved.getInspectionNumber()).as("Hibernate 插入后回读数据库发的号").isEqualTo(prefix + "0008");
        assertThat(number(saved.getId())).isEqualTo(prefix + "0008");
        // 别的工厂同一天独立从 0001 起
        QualityInspection other = new TransactionTemplate(transactionManager)
                .execute(status -> repository.saveAndFlush(inspection(OTHER_FACTORY)));
        assertThat(other.getInspectionNumber()).isEqualTo(prefix + "0001");
        // 应用改不了它: updatable=false
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            QualityInspection loaded = repository.findById(saved.getId()).orElseThrow();
            loaded.setInspectionNumber("QI-HACKED");
            loaded.setNotes("touched");
            repository.saveAndFlush(loaded);
        });
        assertThat(number(saved.getId())).isEqualTo(prefix + "0008");

        // 列表搜索: 完整编号 / 序号片段都能搜到, 别的工厂的同号搜不到
        assertThat(repository.searchForAdminList(FACTORY, null, null, null, null, (prefix + "0008").toLowerCase(), null,
                PageRequest.of(0, 20)).getContent()).extracting(QualityInspection::getId).containsExactly(saved.getId());
        assertThat(repository.searchForAdminList(OTHER_FACTORY, null, null, null, null, prefix + "0008", null,
                PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    @Test
    void twoInsertsInOneTransactionGetConsecutiveNumbers() {
        migrate();
        List<String> ids = new TransactionTemplate(transactionManager).execute(status -> {
            QualityInspection a = repository.save(inspection(FACTORY));
            QualityInspection b = repository.save(inspection(FACTORY));
            return List.of(a.getId(), b.getId());
        });
        assertThat(ids).hasSize(2);
        assertThat(List.of(suffix(number(ids.get(0))), suffix(number(ids.get(1)))))
                .containsExactlyInAnyOrder("0001", "0002");
    }

    @Test
    void concurrentTransactionsAreSerializedAndNeverShareANumber() throws Exception {
        migrate();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> tx.execute(status -> {
                QualityInspection saved = repository.saveAndFlush(inspection(FACTORY));
                firstInserted.countDown();
                await(releaseFirst);
                return saved.getInspectionNumber();
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).as("first transaction did not insert").isTrue();
            Future<String> second = pool.submit(() -> tx.execute(status ->
                    repository.saveAndFlush(inspection(FACTORY)).getInspectionNumber()));
            Thread.sleep(400L);
            assertThat(second.isDone()).as("second insert must wait for the first transaction (same factory, same day)").isFalse();
            releaseFirst.countDown();
            String a = first.get(10, TimeUnit.SECONDS);
            String b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(suffix(a), suffix(b))).containsExactly("0001", "0002");
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT inspection_number) FROM quality_inspections WHERE factory_id = ?",
                Integer.class, FACTORY)).isEqualTo(2);
    }

    @Test
    void defectListNumberLookupStaysInsideFactoryAndKeepsSoftDeletedNumbers() {
        migrate();
        jdbc.update("INSERT INTO quality_inspections (id, inspection_number, factory_id, production_batch_id, inspector_id, "
                + "inspection_date, sample_size, pass_count, fail_count, result, created_at, updated_at, deleted_at) VALUES "
                + "('N-OWN', 'QI-20261110-0001', ?, 1, 1, CURRENT_DATE, 1, 1, 0, 'PASS', NOW(), NOW(), NULL), "
                + "('N-DEL', 'QI-20261110-0002', ?, 1, 1, CURRENT_DATE, 1, 1, 0, 'PASS', NOW(), NOW(), NOW()), "
                + "('N-OTHER', 'QI-20261110-0001', ?, 1, 1, CURRENT_DATE, 1, 1, 0, 'PASS', NOW(), NOW(), NULL)",
                FACTORY, FACTORY, OTHER_FACTORY);

        java.util.Map<String, String> numbers = new java.util.HashMap<>();
        for (Object[] row : repository.findInspectionNumbersByFactoryIdAndIdIn(FACTORY, List.of("N-OWN", "N-DEL", "N-OTHER"))) {
            numbers.put((String) row[0], (String) row[1]);
        }
        // 本厂的 (含已软删的那条) 拿得到编号; 别厂质检记录的编号即使 id 被传进来也拿不到
        assertThat(numbers).containsOnly(
                java.util.Map.entry("N-OWN", "QI-20261110-0001"),
                java.util.Map.entry("N-DEL", "QI-20261110-0002"));
    }

    private void migrate() {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/flyway").baselineOnMigrate(true).baselineVersion("20261119.85")
                .target("20261119.86").outOfOrder(false).load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20261119.86' AND success",
                Integer.class)).as("V20261119_86 must actually be applied").isEqualTo(1);
    }

    private void legacyRow(String id, String factoryId, LocalDateTime createdAt, LocalDateTime deletedAt, LocalDateTime updatedAt) {
        jdbc.update("INSERT INTO quality_inspections (id, factory_id, production_batch_id, inspector_id, inspection_date, "
                        + "sample_size, pass_count, fail_count, result, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, 1, 1, ?, 1, 1, 0, 'PASS', ?, ?, ?)",
                id, factoryId, createdAt.toLocalDate(), Timestamp.valueOf(createdAt), Timestamp.valueOf(updatedAt),
                deletedAt == null ? null : Timestamp.valueOf(deletedAt));
    }

    private static QualityInspection inspection(String factoryId) {
        QualityInspection q = new QualityInspection();
        q.setFactoryId(factoryId);
        q.setProductionBatchId(1L);
        q.setInspectorId(1L);
        q.setInspectionDate(LocalDate.now());
        q.setSampleSize(BigDecimal.ONE);
        q.setPassCount(BigDecimal.ONE);
        q.setFailCount(BigDecimal.ZERO);
        q.setResult("PASS");
        return q;
    }

    private String number(String id) {
        return jdbc.queryForObject("SELECT inspection_number FROM quality_inspections WHERE id = ?", String.class, id);
    }

    private static String suffix(String number) {
        assertThat(number).matches("QI-\\d{8}-\\d{4}");
        return number.substring(number.length() - 4);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static java.sql.Connection adminConnection() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        return DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
