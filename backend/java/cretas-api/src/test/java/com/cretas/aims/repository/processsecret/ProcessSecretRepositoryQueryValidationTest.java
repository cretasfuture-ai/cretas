package com.cretas.aims.repository.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretExternalPackage;
import com.cretas.aims.entity.processsecret.ProcessSecretExternalResult;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPackageStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretResultReviewStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 Hibernate 解析全部工艺保密查询 + 关键约束 (H2; CI 设 CRETAS_WORKFLOW_PG_URL 时跑真 PostgreSQL)。
 * 守的行为: 过期 / 撤销 / 他人 / 他厂的授权查不出来; 一产品一策略; 同厂同事件只收一次。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = ProcessSecretPolicy.class)
@EnableJpaRepositories(basePackageClasses = ProcessSecretPolicyRepository.class)
class ProcessSecretRepositoryQueryValidationTest {

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
        } else {
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:process_secret_repo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        }
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretGrantRepository grants;
    @Autowired ProcessSecretAccessEventRepository events;
    @Autowired ProcessSecretExternalPackageRepository packages;
    @Autowired ProcessSecretExternalResultRepository results;

    ProcessSecretGrant grant(String factory, long grantee, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        return grants.saveAndFlush(ProcessSecretGrant.builder().factoryId(factory).productTypeId("P17")
                .granteeType(ProcessSecretGranteeType.FACTORY_USER).granteeId(grantee)
                .canViewFull(true).purpose("q").expiresAt(expiresAt).grantedBy(1L).revokedAt(revokedAt)
                .build());
    }

    @Test
    void activeGrantQueryExcludesExpiredRevokedOtherGranteeAndOtherFactory() {
        LocalDateTime now = LocalDateTime.now();
        ProcessSecretGrant live = grant("F-A", 20, now.plusDays(1), null);
        grant("F-A", 20, now.minusMinutes(1), null);
        grant("F-A", 20, now.plusDays(1), now.minusMinutes(1));
        grant("F-A", 21, now.plusDays(1), null);
        grant("F-B", 20, now.plusDays(1), null);

        assertThat(grants.findActive("F-A", "P17", ProcessSecretGranteeType.FACTORY_USER, 20L, now))
                .extracting(ProcessSecretGrant::getId).containsExactly(live.getId());
        assertThat(grants.findActive("F-A", "P17", ProcessSecretGranteeType.PLATFORM_ADMIN, 20L, now)).isEmpty();
        // 阳性对照: 21 号自己的那条在
        assertThat(grants.findActive("F-A", "P17", ProcessSecretGranteeType.FACTORY_USER, 21L, now)).hasSize(1);

        // F-A/P17 未撤销的: live、已过期 (过期 ≠ 撤销)、21 号 —— 共 3 条; 已撤销那条与 F-B 不动
        assertThat(grants.revokeAllForProduct("F-A", "P17", now, 1L)).isEqualTo(3);
        grants.flush();
        assertThat(grants.findActive("F-A", "P17", ProcessSecretGranteeType.FACTORY_USER, 20L, now)).isEmpty();
        assertThat(grants.findActive("F-B", "P17", ProcessSecretGranteeType.FACTORY_USER, 20L, now)).hasSize(1);
    }

    @Test
    void onePolicyPerProductAndLockQueryParses() {
        ProcessSecretPolicy saved = policies.saveAndFlush(ProcessSecretPolicy.builder().factoryId("F-A")
                .productTypeId("P17").storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(10L)
                .createdBy(11L).build());
        assertThat(saved.getPolicyVersion()).isEqualTo(1L);
        assertThat(policies.findForUpdate("F-A", "P17")).isPresent();
        assertThat(policies.findForUpdate("F-B", "P17")).isEmpty();
        assertThat(policies.existsByFactoryId("F-A")).isTrue();
        assertThatThrownBy(() -> policies.saveAndFlush(ProcessSecretPolicy.builder().factoryId("F-A")
                .productTypeId("P17").storageMode(ProcessSecretStorageMode.CUSTOMER_HELD).ownerUserId(12L)
                .createdBy(11L).build())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventIdIsUniquePerFactoryAndAuditPagingParses() {
        ProcessSecretExternalPackage pkg = packages.saveAndFlush(ProcessSecretExternalPackage.builder()
                .factoryId("F-A").productTypeId("P17").packageRef("P17-PREMIX").packageVersion("v1")
                .outputUnit("kg").executorPublicKey("k").createdBy(10L).build());
        assertThat(pkg.getStatus()).isEqualTo(ProcessSecretPackageStatus.ACTIVE);
        assertThat(packages.findByFactoryIdAndPackageRefAndPackageVersion("F-A", "P17-PREMIX", "v1")).isPresent();
        assertThat(packages.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc("F-A", "P17")).hasSize(1);

        ProcessSecretExternalResult first = results.saveAndFlush(result(pkg.getId(), "F-A", "evt-1"));
        assertThat(first.getReviewStatus()).isEqualTo(ProcessSecretResultReviewStatus.PENDING_REVIEW);
        results.saveAndFlush(result(pkg.getId(), "F-B", "evt-1"));
        assertThat(results.findByFactoryIdAndEventId("F-A", "evt-1")).isPresent();
        assertThat(results.findByFactoryIdAndPackageIdOrderByReceivedAtDesc("F-A", pkg.getId())).hasSize(1);
        events.saveAndFlush(ProcessSecretAccessEvent.builder().factoryId("F-A").productTypeId("P17")
                .actorType("FACTORY_USER").actorId(20L).action(ProcessSecretAction.VIEW_FULL).decision("DENY")
                .build());
        assertThat(events.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc("F-A", "P17", PageRequest.of(0, 5)))
                .hasSize(1);
        // 唯一约束最后断言: 违约后会话不可再 flush
        assertThatThrownBy(() -> results.saveAndFlush(result(pkg.getId(), "F-A", "evt-1")))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    private static ProcessSecretExternalResult result(Long packageId, String factory, String eventId) {
        return ProcessSecretExternalResult.builder().factoryId(factory).packageId(packageId).eventId(eventId)
                .productionBatchId(300L).outputQuantity(new BigDecimal("1.5")).outputUnit("kg")
                .signedAt(LocalDateTime.now()).payloadDigest("d").submittedBy(20L).build();
    }
}
