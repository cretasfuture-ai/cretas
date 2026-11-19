package com.cretas.aims.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 PostgreSQL: product_types (factory_id, code) 唯一约束必须叫 uk_product_types_factory_code ——
 * GlobalExceptionHandler 靠这个名字给 SKU 编码冲突提示「留空自动生成」(#1873)。
 *
 * <p>prod 的表是 Hibernate 按未命名 @UniqueConstraint 建的, 实名是随机串 (只有唯一索引, 没有约束);
 * 迁移 V20261119_97 要把它改名。本用例: ① 实体建出的 schema 就是这个名字; ② 模拟 prod 的随机名唯一索引,
 * 跑迁移原文后改成目标名; ③ 再跑一次不报错不改动 (幂等)。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductTypeUniqueCodeConstraintNamePostgresContractTest {

    private static final String TARGET = "uk_product_types_factory_code";
    private static final String PROD_LIKE_RANDOM = "ukd96kjf87wgjnvn95q1qyom8pg";
    private static final String MIGRATION =
            "db/flyway/V20261119_97__product_types_unique_code_constraint_name.sql";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String bound = com.cretas.aims.integration.DisposablePostgresTargetGuard.requireSafeUrl(
                System.getenv("CRETAS_WORKFLOW_PG_URL")) + "?stringtype=unspecified";
        registry.add("spring.datasource.url", () -> bound);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void restoreEntityShape() {
        // 恢复成实体建出的形态 (命名约束), 不影响同库后续用例
        jdbc.execute("DROP INDEX IF EXISTS " + PROD_LIKE_RANDOM);
        if (relationCount(TARGET) == 0) {
            jdbc.execute("ALTER TABLE product_types ADD CONSTRAINT " + TARGET + " UNIQUE (factory_id, code)");
        }
    }

    @Test
    void entityCreatedSchemaUsesTheNameTheExceptionHandlerMatches() {
        assertThat(uniqueNamesOnProductTypes()).as("实体建出的 product_types 唯一约束名").contains(TARGET);
    }

    @Test
    void migrationRenamesProdLikeRandomUniqueIndexAndIsIdempotent() throws Exception {
        // 模拟 prod: 只有随机名唯一索引, 没有命名约束
        jdbc.execute("ALTER TABLE product_types DROP CONSTRAINT " + TARGET);
        jdbc.execute("CREATE UNIQUE INDEX " + PROD_LIKE_RANDOM + " ON product_types (factory_id, code)");
        assertThat(relationCount(TARGET)).isZero();
        assertThat(relationCount(PROD_LIKE_RANDOM)).isEqualTo(1);

        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(sql);

        assertThat(relationCount(PROD_LIKE_RANDOM)).as("随机名索引应已改名").isZero();
        assertThat(relationCount(TARGET)).as("改名后的唯一索引").isEqualTo(1);

        jdbc.execute(sql); // 幂等: 已是目标名, 不报错
        assertThat(relationCount(TARGET)).isEqualTo(1);
    }

    private int relationCount(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace "
                        + "WHERE c.relname = ? AND ns.nspname = current_schema()", Integer.class, name);
        return n == null ? 0 : n;
    }

    private java.util.List<String> uniqueNamesOnProductTypes() {
        return jdbc.queryForList(
                "SELECT ic.relname FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                        + "WHERE i.indrelid = 'product_types'::regclass AND i.indisunique AND NOT i.indisprimary",
                String.class);
    }
}
