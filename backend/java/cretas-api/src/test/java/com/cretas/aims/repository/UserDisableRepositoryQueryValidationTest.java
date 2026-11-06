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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Hibernate startup and execution gate for the conditional deactivation query. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {User.class, Factory.class})
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class UserDisableRepositoryQueryValidationTest {
    @Autowired TestEntityManager entityManager;
    @Autowired UserRepository repository;

    @DynamicPropertySource
    static void h2SchemaCompatibility(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:user_disable_query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;"
                        + "NON_KEYWORDS=MONTH,YEAR,VALUE;"
                        + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
    }

    @Test
    void atomicUpdateChangesOnlyActiveSameFactoryUnprotectedUser() {
        persistFactory("F-DISABLE-A");
        User target = persistUser("F-DISABLE-A", "ordinary", "operator", null, true);
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 10, 15);
        entityManager.flush();

        assertThat(repository.deactivateIfEligible("F-DISABLE-A", target.getId(), now)).isEqualTo(1);
        entityManager.clear();

        User reloaded = entityManager.find(User.class, target.getId());
        assertThat(reloaded.getIsActive()).isFalse();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void atomicUpdateIsTenantActiveSoftDeleteAndProtectedRoleSafe() {
        persistFactory("F-DISABLE-B");
        persistFactory("F-DISABLE-C");
        User otherFactory = persistUser("F-DISABLE-C", "other", "operator", null, true);
        User inactive = persistUser("F-DISABLE-B", "inactive", "operator", null, false);
        User roleProtected = persistUser("F-DISABLE-B", "role-protected", " FACTORY_SUPER_ADMIN ", null, true);
        User positionProtected = persistUser("F-DISABLE-B", "position-protected", null, " 超级管理员 ", true);
        User permissionAdmin = persistUser("F-DISABLE-B", "permission-admin", "permission_admin", null, true);
        User deleted = persistUser("F-DISABLE-B", "deleted", "operator", null, true);
        deleted.setDeletedAt(LocalDateTime.now());
        entityManager.flush();

        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 10, 20);
        assertThat(repository.deactivateIfEligible("F-DISABLE-B", otherFactory.getId(), now)).isZero();
        assertThat(repository.deactivateIfEligible("F-DISABLE-B", inactive.getId(), now)).isZero();
        assertThat(repository.deactivateIfEligible("F-DISABLE-B", roleProtected.getId(), now)).isZero();
        assertThat(repository.deactivateIfEligible("F-DISABLE-B", positionProtected.getId(), now)).isZero();
        assertThat(repository.deactivateIfEligible("F-DISABLE-B", deleted.getId(), now)).isZero();
        assertThat(repository.deactivateIfEligible("F-DISABLE-B", permissionAdmin.getId(), now)).isEqualTo(1);
    }

    private void persistFactory(String id) {
        Factory factory = new Factory();
        factory.setId(id);
        factory.setName(id);
        factory.setType(FactoryType.FACTORY);
        factory.setLevel(0);
        factory.setIsActive(true);
        factory.setManuallyVerified(false);
        factory.setAiWeeklyQuota(20);
        entityManager.persist(factory);
    }

    private User persistUser(String factoryId, String username, String roleCode, String position, Boolean active) {
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setRoleCode(roleCode);
        user.setPosition(position);
        user.setIsActive(active);
        entityManager.persist(user);
        return user;
    }
}
