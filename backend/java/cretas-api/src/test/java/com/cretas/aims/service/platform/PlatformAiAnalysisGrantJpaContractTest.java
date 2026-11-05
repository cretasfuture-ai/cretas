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
    @Autowired PlatformFactoryControlAuthority authority;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    org.springframework.transaction.support.TransactionTemplate tx;

    PlatformAdmin superAdmin;
    PlatformAdmin staff;
    AuthenticatedActor manager;

    @BeforeEach
    void seed() {
        tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        superAdmin = admins.saveAndFlush(admin(PlatformRole.super_admin));
        staff = admins.saveAndFlush(admin(PlatformRole.auditor));
        manager = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, superAdmin.getId(), "PLATFORM", "super_admin");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM platform_ai_analysis_grants");
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

    /** 真实表上走一遍: 账号升成超管、账号行被删, 两条授权都撤得掉, 各一条留痕; 升上去的人再降回来也没有授权。 */
    @Test
    void grantsOfAPromotedAndADeletedAccountCanBeRevoked() {
        PlatformAdmin gone = admins.saveAndFlush(admin(PlatformRole.auditor));
        service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ACCOUNT", String.valueOf(staff.getId()), "专人"));
        service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ACCOUNT", String.valueOf(gone.getId()), "专人"));
        staff.setPlatformRole(PlatformRole.super_admin);
        staff = admins.saveAndFlush(staff);
        jdbc.update("DELETE FROM platform_admins WHERE id = ?", gone.getId());

        service.revoke(manager, new PlatformAiAnalysisGrantService.GrantRequest("ACCOUNT", String.valueOf(staff.getId()), "升了超管"));
        service.revoke(manager, new PlatformAiAnalysisGrantService.GrantRequest("ACCOUNT", String.valueOf(gone.getId()), "人走了"));

        assertThat(grants.findAll()).isEmpty();
        assertThat(audits.findTop50ByOrderByChangedAtDescIdDesc()).extracting(PlatformAiAnalysisGrantAudit::getAction)
                .containsExactlyInAnyOrder("GRANT", "GRANT", "REVOKE", "REVOKE");

        staff.setPlatformRole(PlatformRole.auditor);
        staff = admins.saveAndFlush(staff);
        assertThatThrownBy(() -> service.requireEntry(staffRequest())).hasMessage(PlatformAiAnalysisGrantService.NOT_GRANTED_MESSAGE);
    }

    @Test
    void anAccountGrantIsStoredByAccountId() {
        service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ACCOUNT", String.valueOf(staff.getId()), "专人"));
        assertThat(grants.existsByGranteeTypeAndGranteeRef("ACCOUNT", String.valueOf(staff.getId()))).isTrue();
        assertThat(service.requireEntry(staffRequest()).id()).isEqualTo(staff.getId());
        assertThat(service.list(manager).grants()).singleElement()
                .satisfies(g -> assertThat(g.grantedBy()).isEqualTo(superAdmin.getId()));
    }

    /**
     * 并发开通的那个窗口: 查的时候还没有, 写的时候别人已经写了。后到的一方必须拿到 409, 不留痕,
     * 也不改写已有行的开通人和理由 (默认的 save 会先查后 merge, 悄悄改掉)。这里用只在查询上说谎的仓库把窗口固定住。
     */
    @Test
    void aGrantThatLosesTheRaceIsRefusedAndChangesNothing() {
        PlatformAdmin second = admins.saveAndFlush(admin(PlatformRole.super_admin));
        try {
            AuthenticatedActor other = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, second.getId(), "PLATFORM", "super_admin");
            service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "先到的"));
            PlatformAiAnalysisGrantRepository blind = (PlatformAiAnalysisGrantRepository) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{PlatformAiAnalysisGrantRepository.class}, (proxy, method, args) -> {
                        if (method.getName().equals("existsByGranteeTypeAndGranteeRef")) return false;
                        try {
                            return method.invoke(grants, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            PlatformAiAnalysisGrantService racing = new PlatformAiAnalysisGrantService(blind, audits, admins, authority);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> racing.grant(other,
                    new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "后到的"))))
                    .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                    .hasMessage(PlatformAiAnalysisGrantService.ALREADY_GRANTED_MESSAGE);

            assertThat(grants.findAll()).singleElement().satisfies(row -> {
                assertThat(row.getGrantedBy()).isEqualTo(superAdmin.getId());
                assertThat(row.getReason()).isEqualTo("先到的");
            });
            assertThat(audits.findTop50ByOrderByChangedAtDescIdDesc()).singleElement()
                    .satisfies(row -> assertThat(row.getReason()).isEqualTo("先到的"));
        } finally {
            jdbc.update("DELETE FROM platform_admins WHERE id = ?", second.getId());
        }
    }

    /**
     * 普通的重复开通在写之前就挡下: 撞主键那条路只留给并发窗口。两条路对外都是 409 且不留痕, 只有写入次数分得开,
     * 所以这里数 saveAndFlush。第一次开通必须计到 1, 否则「重复时没写」这个读数说明不了什么。
     */
    @Test
    void aPlainRepeatGrantIsRefusedBeforeAnyWriteIsTried() {
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        PlatformAiAnalysisGrantRepository counting = (PlatformAiAnalysisGrantRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PlatformAiAnalysisGrantRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("saveAndFlush")) writes.incrementAndGet();
                    try {
                        return method.invoke(grants, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        PlatformAiAnalysisGrantService counted = new PlatformAiAnalysisGrantService(counting, audits, admins, authority);

        tx.executeWithoutResult(s -> counted.grant(manager,
                new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "先到的")));
        assertThat(writes).as("第一次开通确实经过这个计数").hasValue(1);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> counted.grant(manager,
                new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "再开一次"))))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessage(PlatformAiAnalysisGrantService.ALREADY_GRANTED_MESSAGE);

        assertThat(writes).as("重复开通没有去试写").hasValue(1);
        assertThat(grants.findAll()).singleElement().satisfies(row -> assertThat(row.getReason()).isEqualTo("先到的"));
        assertThat(audits.findTop50ByOrderByChangedAtDescIdDesc()).singleElement()
                .satisfies(row -> assertThat(row.getReason()).isEqualTo("先到的"));
    }

    /** 真的并发: 8 个线程同时开通同一个角色, 恰好一行、一条留痕, 其余都是 409。 */
    @Test
    void concurrentDuplicateGrantsLeaveExactlyOneRowAndOneAuditRow() throws Exception {
        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.grant(manager, new PlatformAiAnalysisGrantService.GrantRequest("ROLE", "auditor", "第 " + n + " 个"));
                    return "OK";
                } catch (com.cretas.aims.exception.BusinessException refused) {
                    return refused.getCode() + ":" + refused.getMessage();
                }
            }));
        }
        start.countDown();
        java.util.List<String> outcomes = new java.util.ArrayList<>();
        for (var result : results) outcomes.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !"OK".equals(o)).hasSize(threads - 1)
                .allSatisfy(o -> assertThat(o).isEqualTo("409:" + PlatformAiAnalysisGrantService.ALREADY_GRANTED_MESSAGE));
        assertThat(grants.findAll()).hasSize(1);
        assertThat(audits.findTop50ByOrderByChangedAtDescIdDesc()).hasSize(1);
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
