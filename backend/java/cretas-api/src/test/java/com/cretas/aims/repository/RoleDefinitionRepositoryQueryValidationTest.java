package com.cretas.aims.repository;

import com.cretas.aims.entity.config.RoleDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约闸: {@link RoleDefinitionRepository} 的查询要在真实 Hibernate/H2 schema 上跑得通.
 *
 * <p>2026-09-04 缺陷背景: {@code role_definitions} 表在 {@code cretas_prod_db} 上从未真正
 * 存在过, {@code DataScopeResolver} 用裸 {@code JdbcTemplate} 字符串查它, 生产每隔几秒
 * WARN 一次 {@code relation "role_definitions" does not exist}。裸 SQL 字符串没有任何
 * 编译期或 CI 期的校验能抓住这种表结构漂移 —— 这正是这道闸存在的理由: 把
 * {@code role_definitions} 升级成真正的 JPA entity 之后, 这里的 H2 (Hibernate
 * {@code ddl-auto}) schema 就会真的按 {@link RoleDefinition} 建出这张表, 任何字段/
 * 类型层面的漂移都会在这道闸上现形, 而不是要等到生产才炸。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class RoleDefinitionRepositoryQueryValidationTest {

    @Autowired TestEntityManager entityManager;
    @Autowired RoleDefinitionRepository repository;

    @Test
    void findByRoleCodeReturnsTheConfiguredDataScope() {
        persist("factory_super_admin", "工厂总监", 0, "all", "ALL");
        persist("yield_operator", "报工操作员", 30, "production", "SELF");
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByRoleCode("factory_super_admin"))
                .get()
                .extracting(RoleDefinition::getDataScope)
                .isEqualTo("ALL");

        // 阳性对照的关键行: 这是本轮实测发现的真实缺陷 —— yield_operator 此前落到
        // DataScopeResolver 硬默认 switch 的 default 分支拿到了 ALL 而不是 SELF。
        // 这里钉住种子/配置数据本身的口径是对的 (SELF); DataScopeResolverTest 另
        // 钉住"解析器读到这一行之后真的返回 SELF"。
        assertThat(repository.findByRoleCode("yield_operator"))
                .get()
                .extracting(RoleDefinition::getDataScope)
                .isEqualTo("SELF");
    }

    @Test
    void findByRoleCodeIsEmptyForUnconfiguredRole() {
        persist("factory_super_admin", "工厂总监", 0, "all", "ALL");
        entityManager.flush();
        entityManager.clear();

        // 阴性对照: 没配置的角色查得到"没有这一行", 不是抛异常, 不是误命中别的角色.
        assertThat(repository.findByRoleCode("some_unconfigured_role")).isEmpty();
    }

    private void persist(String roleCode, String displayName, int level, String department, String dataScope) {
        RoleDefinition rd = RoleDefinition.builder()
                .roleCode(roleCode)
                .displayName(displayName)
                .level(level)
                .department(department)
                .isDeprecated(false)
                .dataScope(dataScope)
                .build();
        entityManager.persist(rd);
    }
}
