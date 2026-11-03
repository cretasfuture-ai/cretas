package com.cretas.aims.repository.learning;

import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 训练样本过滤 (2026-10-29 选 A) 新增的两个仓库在真实 JPA 映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑):
 *
 * <ul>
 *   <li>{@link TrainingSampleSecrecyHoldRepository}: 按挂起先后取一批 (重判任务先判最老的), 按工厂计数;</li>
 *   <li>{@link FactoryNameDirectoryRepository}: 本厂人员姓名 / 客户名称 —— 只取本厂, <b>包含已软删除的行</b>
 *       (离职员工、停用客户的名字照样是本厂的人和客户; JPQL 会被 {@code @Where} 过滤掉, 所以是原生查询)。</li>
 * </ul>
 * 实体与迁移 (V20261104_11) 的列一致由 {@code TrainingSampleSecrecyHoldMigrationPostgresContractTest} 在真 PG 上校验。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackageClasses = TrainingSampleSecrecyHoldRepository.class, includeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {TrainingSampleSecrecyHoldRepository.class, FactoryNameDirectoryRepository.class}))
class TrainingSampleSecrecyRepositoryQueryValidationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @Autowired TestEntityManager em;
    @Autowired TrainingSampleSecrecyHoldRepository holds;
    @Autowired FactoryNameDirectoryRepository directory;

    private TrainingSampleSecrecyHold hold(String factory, LocalDateTime at) {
        return holds.saveAndFlush(TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.INTENT, factory,
                "{\"userInput\":\"x\"}", false, at));
    }

    @Test
    void oldestHoldsComeFirstAndCountsAreScopedByFactory() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 3, 8, 0);
        TrainingSampleSecrecyHold newer = hold("F006", t.plusMinutes(5));
        TrainingSampleSecrecyHold oldest = hold("F006", t);
        TrainingSampleSecrecyHold other = hold("F007", t.plusMinutes(1));
        em.clear();

        assertThat(holds.findOldest(PageRequest.of(0, 2))).extracting(TrainingSampleSecrecyHold::getId)
                .containsExactly(oldest.getId(), other.getId());
        assertThat(holds.findOldest(PageRequest.of(0, 10))).hasSize(3);
        assertThat(holds.countByFactoryId("F006")).isEqualTo(2);
        assertThat(holds.countByFactoryId("F999")).isZero();

        TrainingSampleSecrecyHold reread = holds.findById(newer.getId()).orElseThrow();
        assertThat(reread.getSampleKind()).isEqualTo(TrainingSampleSecrecyHold.Kind.INTENT);
        assertThat(reread.getPayload()).isEqualTo("{\"userInput\":\"x\"}");
        assertThat(reread.getAttempts()).isZero();
        assertThat(reread.isAuthorizedAtHold()).isFalse();
    }

    private void factory(String id) {
        em.getEntityManager().createNativeQuery("INSERT INTO factories (id, name, type, level, is_active,"
                        + " manually_verified, ai_weekly_quota, created_at, updated_at)"
                        + " VALUES (?1, ?1, 'FACTORY', 0, true, false, 0, ?2, ?2)")
                .setParameter(1, id).setParameter(2, LocalDateTime.of(2026, 1, 1, 0, 0))
                .executeUpdate();
    }

    private void user(long id, String factory, String username, String fullName, boolean deleted) {
        em.getEntityManager().createNativeQuery("INSERT INTO users (id, factory_id, username, password_hash, is_active,"
                        + " full_name, created_at, updated_at, deleted_at) VALUES (?1, ?2, ?3, 'x', true, ?4, ?5, ?5, ?6)")
                .setParameter(1, id).setParameter(2, factory).setParameter(3, username).setParameter(4, fullName)
                .setParameter(5, LocalDateTime.of(2026, 1, 1, 0, 0))
                .setParameter(6, deleted ? LocalDateTime.of(2026, 6, 1, 0, 0) : null)
                .executeUpdate();
    }

    private void customer(String id, String factory, String name, boolean deleted) {
        em.getEntityManager().createNativeQuery("INSERT INTO customers (id, factory_id, code, customer_code, name,"
                        + " is_active, created_by, version, created_at, updated_at, deleted_at)"
                        + " VALUES (?1, ?2, ?1, ?1, ?3, true, 9001, 0, ?4, ?4, ?5)")
                .setParameter(1, id).setParameter(2, factory).setParameter(3, name)
                .setParameter(4, LocalDateTime.of(2026, 1, 1, 0, 0))
                .setParameter(5, deleted ? LocalDateTime.of(2026, 6, 1, 0, 0) : null)
                .executeUpdate();
    }

    @Test
    void directoryReturnsTheFactorysOwnPeopleAndCustomersIncludingSoftDeletedOnes() {
        factory("F006");
        factory("F007");
        user(9001, "F006", "u9001", "王五", false);
        user(9002, "F006", "u9002", "张三", true);          // left the company: still F006's person
        user(9003, "F007", "u9003", "李四", false);         // another factory
        user(9004, "F006", "u9004", null, false);           // no name on file
        customer("C9001", "F006", "华联超市", false);
        customer("C9002", "F006", "张记批发", true);         // deactivated customer: still F006's
        customer("C9003", "F007", "别厂客户", false);

        assertThat(directory.findPeopleNames("F006")).containsExactlyInAnyOrder("王五", "张三");
        assertThat(directory.findCustomerNames("F006")).containsExactlyInAnyOrder("华联超市", "张记批发");
        assertThat(directory.findPeopleNames("F007")).containsExactly("李四");
        assertThat(directory.findPeopleNames("F999")).isEmpty();
    }
}
