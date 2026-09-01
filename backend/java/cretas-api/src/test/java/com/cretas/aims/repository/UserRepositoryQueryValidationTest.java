package com.cretas.aims.repository;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate/JPA startup and tenant-isolation gate for user object lookups. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class UserRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired UserRepository repository;

    @Test
    void findByIdAndFactoryIdReturnsOnlyTheOwningTenantUser() {
        persistFactory("F-JPA-USER-A", "JPA user query gate A");
        persistFactory("F-JPA-USER-B", "JPA user query gate B");
        User tenantAUser = persistUser("F-JPA-USER-A", "jpa-user-query-a");
        User tenantBUser = persistUser("F-JPA-USER-B", "jpa-user-query-b");
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdAndFactoryId(tenantAUser.getId(), "F-JPA-USER-A"))
                .get()
                .extracting(User::getUsername)
                .isEqualTo("jpa-user-query-a");
        assertThat(repository.findByIdAndFactoryId(tenantAUser.getId(), "F-JPA-USER-B"))
                .isEmpty();
        assertThat(repository.findByIdAndFactoryId(tenantBUser.getId(), "F-JPA-USER-A"))
                .isEmpty();
        assertThat(repository.findByIdAndFactoryId(Long.MAX_VALUE, "F-JPA-USER-A"))
                .isEmpty();
    }

    /**
     * 账号管理页的搜索: 关键词把 baogong* 挡在外面。
     *
     * <p>这条钉的是 <b>SQL 真的在过滤</b> —— 被 mock 掉的 service 测试证明不了它。
     * 现场 (LIUSHANMEN, 2026-09-01): owner 在用户名框输 {@code lsm} 点搜索,
     * 回来的仍是全部 33 条, 第 2 页照样列着 {@code baogong004}–{@code baogong010}。
     *
     * <p>⛔ 判据不是「总数变了」—— 那正是坏掉时的样子(总数一直是全量)。
     * 判据是「回来的每一条都是 lsm*, 且一条 baogong* 都没有」。
     */
    @Test
    void searchByKeywordExcludesAccountsThatDoNotMatch() {
        persistFactory("F-JPA-USER-S", "JPA user search gate");
        persistUser("F-JPA-USER-S", "lsm001", "warehouse_manager");
        persistUser("F-JPA-USER-S", "lsm002", "quality_inspector");
        persistUser("F-JPA-USER-S", "baogong001", "yield_operator");
        persistUser("F-JPA-USER-S", "baogong002", "yield_operator");
        entityManager.flush();
        entityManager.clear();

        Page<User> hits = repository.searchUsersByKeywordAndRole(
                "F-JPA-USER-S", "lsm", "", PageRequest.of(0, 10));

        assertThat(hits.getContent()).extracting(User::getUsername)
                .containsExactlyInAnyOrder("lsm001", "lsm002");
        assertThat(hits.getContent()).extracting(User::getUsername)
                .noneMatch(u -> u.startsWith("baogong"));
        assertThat(hits.getTotalElements()).isEqualTo(2L);

        // 阳性对照: 空关键词确实不过滤 —— 否则上面那条「只回 2 条」可能只是查询恒空。
        Page<User> all = repository.searchUsersByKeywordAndRole(
                "F-JPA-USER-S", "", "", PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(4L);
    }

    /**
     * 角色筛选必须在 SQL 里做, 且 total 是<b>全体</b>符合条件的条数, 不是当前页过滤后的。
     *
     * <p>旧实现在内存里对当前页 filter 并把 total 重算成过滤后的条数 ⇒ 每页 10 条时,
     * 按角色筛选只会从「全厂前 10 个人」里挑。这里刻意把页大小设成 1 来暴露它:
     * 坏的实现会返回 total=1, 正确的实现返回 total=3。
     */
    @Test
    void roleFilterIsAppliedInSqlAndTotalCountsEveryMatchNotJustThisPage() {
        persistFactory("F-JPA-USER-R", "JPA user role filter gate");
        persistUser("F-JPA-USER-R", "rf-bg-1", "yield_operator");
        persistUser("F-JPA-USER-R", "rf-bg-2", "yield_operator");
        persistUser("F-JPA-USER-R", "rf-bg-3", "yield_operator");
        persistUser("F-JPA-USER-R", "rf-wh-1", "warehouse_manager");
        entityManager.flush();
        entityManager.clear();

        Page<User> firstPage = repository.searchUsersByKeywordAndRole(
                "F-JPA-USER-R", "", "yield_operator", PageRequest.of(0, 1));

        assertThat(firstPage.getContent()).hasSize(1);
        assertThat(firstPage.getContent().get(0).getRoleCode()).isEqualTo("yield_operator");
        assertThat(firstPage.getTotalElements())
                .as("total 必须是全体 yield_operator 的条数, 不是当前页过滤后的 1")
                .isEqualTo(3L);

        // 关键词 + 角色同时给出时两个条件是 AND。
        Page<User> both = repository.searchUsersByKeywordAndRole(
                "F-JPA-USER-R", "rf-bg", "warehouse_manager", PageRequest.of(0, 10));
        assertThat(both.getTotalElements()).isZero();
    }

    private void persistFactory(String id, String name) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(name);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private User persistUser(String factoryId, String username) {
        return persistUser(factoryId, username, null);
    }

    private User persistUser(String factoryId, String username, String roleCode) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setIsActive(true);
        user.setRoleCode(roleCode);
        entityManager.persist(user);
        return user;
    }
}
