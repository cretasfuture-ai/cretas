package com.cretas.aims.repository;

import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.integration.DisposablePostgresTargetGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * PostgreSQL proof for {@link QualityInspectionRepository#searchForAdminList} and
 * {@link QualityInspectionRepository#findBatchNumbersHavingInspection}
 * (web-admin 质检记录列表: 关键词 / 批量批次号 / 日期区间 / 结果, 全部分页前过滤, 子查询带 factory_id)。
 *
 * <p>覆盖: 全部条件为空 (typed null 在 PG 上要能绑定)、关键词、批量批次号精确匹配 (不模糊)、
 * 日期区间含两端、结果多值、跨租户同批次号不串、未找到批次号。</p>
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class QualityInspectionAdminListPostgresRepositoryQueryValidationTest {

    private static final String FACTORY = "IT-QI-LIST-PG";
    private static final String OTHER_FACTORY = "IT-QI-LIST-PG-OTHER";
    private static final String SCHEMA = "qi_list_" + UUID.randomUUID().toString().replace("-", "");
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired private QualityInspectionRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        String url = DisposablePostgresTargetGuard.requireSafeUrl(requiredEnv("CRETAS_WORKFLOW_PG_URL"));
        try (var connection = DriverManager.getConnection(url, requiredEnv("CRETAS_WORKFLOW_PG_USER"),
                System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
             var statement = connection.createStatement()) {
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

    private long batchA;
    private long batchB;
    private long batchC;

    @BeforeEach
    void seed() {
        // 只造批次与质检记录, 不造它们的外键目标: 摘掉这个一次性 schema 里 Hibernate 生成的外键
        for (String table : List.of("quality_inspections", "production_batches")) {
            jdbcTemplate.execute("""
                    DO $$ DECLARE r record; BEGIN
                      FOR r IN SELECT conname FROM pg_constraint
                               WHERE conrelid = '%s'::regclass AND contype = 'f' LOOP
                        EXECUTE 'ALTER TABLE %s DROP CONSTRAINT ' || quote_ident(r.conname);
                      END LOOP;
                    END $$""".formatted(table, table));
        }
        jdbcTemplate.update("DELETE FROM quality_inspections");
        jdbcTemplate.update("DELETE FROM production_batches");
        batchA = batch(FACTORY, "PB-QI-001");
        batchB = batch(FACTORY, "PB-QI-0010");   // 与 PB-QI-001 前缀相同, 用来证明批量匹配是精确的
        batchC = batch(FACTORY, "PB-QI-NONE");   // 没有质检记录
        long other = batch(OTHER_FACTORY, "PB-QI-OTHER");
        inspection("QI000001-AAAA", FACTORY, batchA, "PASS", LocalDate.of(2026, 11, 10));
        inspection("QI000002-BBBB", FACTORY, batchA, "FAIL", LocalDate.of(2026, 11, 12));
        inspection("QI000003-CCCC", FACTORY, batchB, "CONDITIONAL", LocalDate.of(2026, 11, 14));
        inspection("QI000009-XXXX", OTHER_FACTORY, other, "PASS", LocalDate.of(2026, 11, 12));
    }

    @Test
    void allFiltersEmptyBindAsTypedNullsAndStayInsideFactory() {
        Page<QualityInspection> page = repository.searchForAdminList(
                FACTORY, null, null, null, null, null, null, PAGE);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(QualityInspection::getFactoryId).containsOnly(FACTORY);
        // 日期倒序
        assertThat(page.getContent()).extracting(QualityInspection::getId)
                .containsExactly("QI000003-CCCC", "QI000002-BBBB", "QI000001-AAAA");
    }

    @Test
    void keywordMatchesInspectionIdPrefixOrBatchNumberContains() {
        assertThat(repository.searchForAdminList(FACTORY, null, null, null, null, "qi000002", null, PAGE)
                .getContent()).extracting(QualityInspection::getId).containsExactly("QI000002-BBBB");
        // 批次号包含: PB-QI-001 同时是 PB-QI-0010 的前缀, 两个批次的记录都命中
        assertThat(repository.searchForAdminList(FACTORY, null, null, null, null, "pb-qi-001", null, PAGE)
                .getTotalElements()).isEqualTo(3);
        // 别的租户的批次号搜不到
        assertThat(repository.searchForAdminList(FACTORY, null, null, null, null, "OTHER", null, PAGE)
                .getTotalElements()).isZero();
    }

    @Test
    void batchNosAreExactNotPrefix() {
        Page<QualityInspection> page = repository.searchForAdminList(
                FACTORY, null, null, null, null, null, List.of("PB-QI-001"), PAGE);
        assertThat(page.getContent()).extracting(QualityInspection::getId)
                .containsExactlyInAnyOrder("QI000001-AAAA", "QI000002-BBBB");
    }

    @Test
    void batchNosDoNotLeakAcrossFactories() {
        assertThat(repository.searchForAdminList(
                FACTORY, null, null, null, null, null, List.of("PB-QI-OTHER"), PAGE).getTotalElements()).isZero();
    }

    @Test
    void dateRangeIsInclusiveOnBothEnds() {
        Page<QualityInspection> page = repository.searchForAdminList(FACTORY, null, null,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), null, null, PAGE);
        assertThat(page.getContent()).extracting(QualityInspection::getId)
                .containsExactly("QI000002-BBBB", "QI000001-AAAA");
        // 只给起点 / 只给终点
        assertThat(repository.searchForAdminList(FACTORY, null, null,
                LocalDate.of(2026, 11, 13), null, null, null, PAGE).getTotalElements()).isEqualTo(1);
        assertThat(repository.searchForAdminList(FACTORY, null, null,
                null, LocalDate.of(2026, 11, 9), null, null, PAGE).getTotalElements()).isZero();
    }

    @Test
    void multipleResultsAndAllFiltersTogether() {
        assertThat(repository.searchForAdminList(FACTORY, null, List.of("FAIL", "CONDITIONAL"),
                null, null, null, null, PAGE).getTotalElements()).isEqualTo(2);
        Page<QualityInspection> page = repository.searchForAdminList(FACTORY, batchA, List.of("FAIL"),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "PB-QI", List.of("PB-QI-001"), PAGE);
        assertThat(page.getContent()).extracting(QualityInspection::getId).containsExactly("QI000002-BBBB");
    }

    @Test
    void batchNumbersHavingInspectionSplitsFoundFromMissing() {
        List<String> found = repository.findBatchNumbersHavingInspection(
                FACTORY, List.of("PB-QI-001", "PB-QI-NONE", "PB-QI-OTHER", "PB-QI-MISSING"));
        // PB-QI-NONE 存在但没有质检记录; PB-QI-OTHER 是别的租户的; 都算「未找到」
        assertThat(found).containsExactly("PB-QI-001");
        assertThat(batchC).isPositive();
        assertThat(batchB).isPositive();
    }

    private long batch(String factoryId, String batchNumber) {
        // 直接 SQL 造批次: 实体上有 jsonb 列, Hibernate 在此一次性 schema 里绑 varchar 会被 PG 拒绝
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO production_batches (factory_id, batch_number, product_type_id, quantity, unit, status,
                                                cost_incomplete, batch_type, created_at, updated_at)
                VALUES (?, ?, 'PT-QI-PG', 10, 'kg', 'COMPLETED', false, 'REGULAR', NOW(), NOW())
                RETURNING id""", Long.class, factoryId, batchNumber);
        return id;
    }

    private void inspection(String id, String factoryId, long batchId, String result, LocalDate date) {
        QualityInspection q = new QualityInspection();
        q.setId(id);
        q.setFactoryId(factoryId);
        q.setProductionBatchId(batchId);
        q.setInspectorId(1L);
        q.setInspectionDate(date);
        q.setSampleSize(BigDecimal.ONE);
        q.setPassCount("PASS".equals(result) ? BigDecimal.ONE : BigDecimal.ZERO);
        q.setFailCount("FAIL".equals(result) ? BigDecimal.ONE : BigDecimal.ZERO);
        q.setResult(result);
        repository.saveAndFlush(q);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when PostgreSQL verification is enabled");
        }
        return value;
    }
}
