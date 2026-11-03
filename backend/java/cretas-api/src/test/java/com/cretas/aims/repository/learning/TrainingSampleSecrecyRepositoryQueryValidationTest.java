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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 训练样本过滤新增 / 改动的两个仓库在真实 JPA 映射上跑 (H2; 设了 CRETAS_WORKFLOW_PG_URL 时在 PG 上跑):
 *
 * <ul>
 *   <li>{@link TrainingSampleSecrecyHoldRepository#findNextPending}: 只取 PENDING; 按「最近一次尝试, 没试过的按挂起时间」
 *       先后 (试过一次的排到后面, 复审 N4c); REJECTED 的行保留但不再取 (Q5);</li>
 *   <li>{@link FactoryNameDirectoryRepository}: 本厂人员 + 客户 / 供应商联系人 (N6); 保密产品原始行<b>含已软删除的产品</b>
 *       (N2: {@code findAllById} 会被 {@code @Where} 过滤掉); 保密策略上登记的别名 (Q1)。全部只取本厂。</li>
 * </ul>
 * 实体与迁移 (V20261104_11 / V20261104_12) 的列一致由 {@code TrainingSampleSecrecyHoldMigrationPostgresContractTest} 在真 PG 上校验。
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

    static final LocalDateTime T = LocalDateTime.of(2026, 11, 3, 8, 0);

    @Autowired TestEntityManager em;
    @Autowired TrainingSampleSecrecyHoldRepository holds;
    @Autowired FactoryNameDirectoryRepository directory;

    private TrainingSampleSecrecyHold hold(String factory, LocalDateTime at) {
        return holds.saveAndFlush(TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.INTENT, factory,
                "{\"userInput\":\"x\"}", at));
    }

    @Test
    void nextPendingSkipsRejectedRowsAndPutsTriedRowsAtTheBack() {
        TrainingSampleSecrecyHold tried = hold("F006", T);                       // oldest, but already tried
        tried.setAttempts(1);
        tried.setLastAttemptAt(T.plusMinutes(30));
        holds.saveAndFlush(tried);
        TrainingSampleSecrecyHold fresh1 = hold("F006", T.plusMinutes(5));
        TrainingSampleSecrecyHold fresh2 = hold("F007", T.plusMinutes(10));
        TrainingSampleSecrecyHold rejected = hold("F006", T.minusMinutes(5));
        rejected.setReviewStatus(TrainingSampleSecrecyHold.ReviewStatus.REJECTED);
        rejected.setRejectReason("names_factory_list");
        rejected.setRejectedAt(T);
        holds.saveAndFlush(rejected);
        em.clear();

        assertThat(holds.findNextPending(PageRequest.of(0, 10))).extracting(TrainingSampleSecrecyHold::getId)
                .containsExactly(fresh1.getId(), fresh2.getId(), tried.getId());
        assertThat(holds.findNextPending(PageRequest.of(0, 1))).extracting(TrainingSampleSecrecyHold::getId)
                .containsExactly(fresh1.getId());
        // the rejected row is kept (manual review), just never picked again
        assertThat(holds.findById(rejected.getId())).isPresent();
        assertThat(holds.countByFactoryIdAndReviewStatus("F006", TrainingSampleSecrecyHold.ReviewStatus.REJECTED)).isEqualTo(1);
        assertThat(holds.countByFactoryId("F006")).isEqualTo(3);

        TrainingSampleSecrecyHold reread = holds.findById(fresh1.getId()).orElseThrow();
        assertThat(reread.getReviewStatus()).isEqualTo(TrainingSampleSecrecyHold.ReviewStatus.PENDING);
        assertThat(reread.getPayload()).isEqualTo("{\"userInput\":\"x\"}");
    }

    private void exec(String sql, Object... params) {
        var q = em.getEntityManager().createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        q.executeUpdate();
    }

    private void factory(String id) {
        exec("INSERT INTO factories (id, name, type, level, is_active, manually_verified, ai_weekly_quota, created_at, updated_at)"
                + " VALUES (?1, ?1, 'FACTORY', 0, true, false, 0, ?2, ?2)", id, T);
    }

    private void user(long id, String factory, String fullName, boolean deleted) {
        exec("INSERT INTO users (id, factory_id, username, password_hash, is_active, full_name, created_at, updated_at, deleted_at)"
                + " VALUES (?1, ?2, ?3, 'x', true, ?4, ?5, ?5, ?6)", id, factory, "u" + id, fullName, T, deleted ? T : null);
    }

    private void customer(String id, String factory, String name, String contactName, String contactPerson, boolean deleted) {
        exec("INSERT INTO customers (id, factory_id, code, customer_code, name, contact_name, contact_person, is_active,"
                + " created_by, version, created_at, updated_at, deleted_at) VALUES (?1, ?2, ?1, ?1, ?3, ?4, ?5, true, 9001, 0, ?6, ?6, ?7)",
                id, factory, name, contactName, contactPerson, T, deleted ? T : null);
    }

    private void supplier(String id, String factory, String contactName, String contactPerson) {
        exec("INSERT INTO suppliers (id, factory_id, code, supplier_code, name, contact_name, contact_person, is_active, version,"
                + " created_by, created_at, updated_at) VALUES (?1, ?2, ?1, ?1, ?1, ?3, ?4, true, 0, 9001, ?5, ?5)",
                id, factory, contactName, contactPerson, T);
    }

    private void product(String id, String factory, String code, String name, boolean deleted) {
        exec("INSERT INTO product_types (id, factory_id, code, name, unit, inspection_required, is_active, created_at,"
                + " updated_at, deleted_at) VALUES (?1, ?2, ?3, ?4, 'kg', false, true, ?5, ?5, ?6)",
                id, factory, code, name, T, deleted ? T : null);
    }

    private void policy(String factory, String productId, String aliases) {
        exec("INSERT INTO process_secret_policies (factory_id, product_type_id, storage_mode, owner_user_id, policy_version,"
                + " created_by, created_at, updated_at, aliases) VALUES (?1, ?2, 'CLOUD_RESTRICTED', 9001, 1, 9001, ?3, ?3, ?4)",
                factory, productId, T, aliases);
    }

    @Test
    void directoryReturnsTheFactorysOwnPeopleContactsAndCustomersIncludingSoftDeletedOnes() {
        factory("F006");
        factory("F007");
        user(9001, "F006", "王五", false);
        user(9002, "F006", "张三", true);          // left the company: still F006's person
        user(9003, "F007", "李四", false);         // another factory
        user(9004, "F006", null, false);           // no name on file
        customer("C9001", "F006", "华联超市", "赵六", null, false);
        customer("C9002", "F006", "张记批发", null, "钱七", true);     // deactivated customer: still F006's
        customer("C9003", "F007", "别厂客户", "孙八", null, false);
        supplier("S9001", "F006", "周九", "吴十");

        assertThat(directory.findPeopleNames("F006")).containsExactlyInAnyOrder("王五", "张三", "赵六", "钱七", "周九", "吴十");
        assertThat(directory.findCustomerNames("F006")).containsExactlyInAnyOrder("华联超市", "张记批发");
        assertThat(directory.findPeopleNames("F007")).containsExactlyInAnyOrder("李四", "孙八");
        assertThat(directory.findPeopleNames("F999")).isEmpty();
    }

    @Test
    void protectedProductRowsIncludeSoftDeletedProductsAndAliasesComeFromTheFactorysPolicies() {
        factory("F006");
        factory("F007");
        user(9001, "F006", "王五", false);
        product("P1", "F006", "PTF0060159", "秘制牛肉干", false);
        product("P2", "F006", "PTF0060160", "停产的保密卤味", true);     // soft-deleted, still protected (N2)
        product("P3", "F007", "PTF0070001", "别厂产品", false);
        policy("F006", "P1", "[\"牛肉干一号\"]");
        policy("F006", "P2", null);
        policy("F007", "P3", "[\"别厂别名\"]");

        List<Object[]> rows = directory.findProtectedProductRows("F006", Set.of("P1", "P2", "P3"));
        assertThat(rows).extracting(r -> (String) r[2]).containsExactlyInAnyOrder("秘制牛肉干", "停产的保密卤味");
        assertThat(rows).extracting(r -> (String) r[1]).containsExactlyInAnyOrder("PTF0060159", "PTF0060160");
        assertThat(directory.findPolicyAliases("F006")).containsExactly("[\"牛肉干一号\"]");
        assertThat(directory.findPolicyAliases("F007")).containsExactly("[\"别厂别名\"]");
    }
}
