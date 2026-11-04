package com.cretas.aims.service.platform;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.enums.Status;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrant;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrantAudit;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.platform.PlatformAiAnalysisGrantAuditRepository;
import com.cretas.aims.repository.platform.PlatformAiAnalysisGrantRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实的 JPA + 真实事务 (经 Spring 代理, 不是测试事务包着): 授权行与留痕同进同退, 撤销后下一次判定就查不到,
 * 没有任何缓存挡在中间。PG 上的触发器与约束在 {@code PlatformAiAnalysisGrantMigrationPostgresContractTest}。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({PlatformAiAnalysisGrantService.class, PlatformFactoryControlAuthority.class, FactoryFeatureMenuWriteLock.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlatformAiAnalysisGrantJpaContractTest {

    @Autowired PlatformAiAnalysisGrantService service;
    @Autowired PlatformAiAnalysisGrantRepository grants;
    @Autowired PlatformAiAnalysisGrantAuditRepository audits;
    @Autowired PlatformAdminRepository admins;
    @Autowired JdbcTemplate jdbc;

    PlatformAdmin superAdmin;
    PlatformAdmin staff;
    AuthenticatedActor manager;

    @BeforeEach
    void seed() {
        superAdmin = admins.saveAndFlush(admin(PlatformRole.super_admin));
        staff = admins.saveAndFlush(admin(PlatformRole.auditor));
        manager = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, superAdmin.getId(), "PLATFORM", "super_admin");
    }

    @AfterEach
    void cleanUp() {
        grants.deleteAll();
        jdbc.update("DELETE FROM platform_ai_analysis_grant_audit");
        admins.deleteById(superAdmin.getId());
        admins.deleteById(staff.getId());
    }

    static PlatformAdmin admin(PlatformRole role) {
        return PlatformAdmin.builder().username("ai_grant_" + UUID.randomUUID().toString().substring(0, 8))
                .passwordHash("x").realName("平台人员").platformRole(role).status(Status.active)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    MockHttpServletRequest staffRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, staff.getId(), "PLATFORM", "auditor"));
        return request;
    }

    @Test
    void aGrantTakesEffectAndARevocationEndsItOnTheNextCheckWithOneAuditRowEach() {
        assertThatThrownBy(() -> service.requireEntry(staffRequest())).hasMessage(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE);

        service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "审计抽查"));
        assertThat(service.requireEntry(staffRequest()).id()).isEqualTo(staff.getId());

        service.revoke(manager, new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "抽查结束"));
        assertThatThrownBy(() -> service.requireEntry(staffRequest())).hasMessage(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE);

        assertThat(grants.findAll()).isEmpty();
        assertThat(audits.findTop50ByOrderByChangedAtDescIdDesc())
                .extracting(PlatformAiAnalysisGrantAudit::getAction).containsExactlyInAnyOrder("GRANT", "REVOKE");
    }

    @Test
    void anAccountGrantIsStoredByAccountId() {
        service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ACCOUNT", String.valueOf(staff.getId()), "专人"));
        assertThat(grants.existsByGranteeTypeAndGranteeRef("ACCOUNT", String.valueOf(staff.getId()))).isTrue();
        assertThat(service.requireEntry(staffRequest()).id()).isEqualTo(staff.getId());
        assertThat(service.list(manager).grants()).singleElement()
                .satisfies(g -> assertThat(g.grantedBy()).isEqualTo(superAdmin.getId()));
    }

    /** 留痕写不进去时授权一起回滚: 服务层的校验挡得住坏输入, 所以让表暂时不可写来造这个失败。 */
    @Test
    void whenTheAuditRowCannotBeWrittenTheGrantRollsBackToo() {
        jdbc.execute("ALTER TABLE platform_ai_analysis_grant_audit RENAME TO platform_ai_analysis_grant_audit_away");
        try {
            assertThatThrownBy(() -> service.grant(manager,
                    new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "审计抽查")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("ALTER TABLE platform_ai_analysis_grant_audit_away RENAME TO platform_ai_analysis_grant_audit");
        }
        assertThat(grants.findAll()).as("授权跟着回滚").isEmpty();
        assertThat(grants.existsById(new PlatformAiAnalysisGrant.Key("ROLE", "auditor"))).isFalse();
    }
}
