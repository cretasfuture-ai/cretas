package com.cretas.aims.repository;

import com.cretas.aims.entity.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.quality.QualityDispositionDecisionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Real Hibernate, transactions and managed-entity refresh; HTTP/PG adds inventory evidence. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import({QualityDispositionDecisionGuard.class, QualityDispositionDecisionGuardRepositoryQueryValidationTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QualityDispositionDecisionGuardRepositoryQueryValidationTest {
    @TestConfiguration static class JsonConfig { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }
    @Autowired QualityDispositionDecisionGuard guard;
    @Autowired QualityInspectionRepository inspections;
    @Autowired DecisionAuditLogRepository audits;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @MockBean DroolsRuleRepository drools;

    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
    private record Sample(String factory, String inspection) {}
    private Sample sample() {
        return tx(() -> {
            String factoryId = "Q" + UUID.randomUUID().toString().substring(0, 8);
            Factory factory = new Factory(); factory.setId(factoryId); factory.setName(factoryId);
            factory.setIsActive(true); factory.setManuallyVerified(false); factory.setAiWeeklyQuota(20); em.persist(factory);
            User user = new User(); user.setFactoryId(factoryId); user.setUsername("qc-" + factoryId);
            user.setPasswordHash("test-only"); user.setIsActive(true); em.persist(user); em.flush();
            ProductionBatch batch = new ProductionBatch(); batch.setFactoryId(factoryId);
            batch.setBatchNumber("Q-" + UUID.randomUUID()); batch.setProductTypeId("TEST-SKU");
            batch.setPlannedQuantity(BigDecimal.ONE); batch.setQuantity(BigDecimal.ONE);
            batch.setCustomFields(null); // Optional JSON is outside this lock fixture; H2 JSONB is not PostgreSQL JSONB.
            batch.setUnit("盒"); em.persist(batch); em.flush();
            QualityInspection inspection = new QualityInspection(); inspection.setId(UUID.randomUUID().toString());
            inspection.setFactoryId(factoryId); inspection.setProductionBatchId(batch.getId());
            inspection.setInspectorId(user.getId()); inspection.setInspectionDate(LocalDate.now());
            inspection.setSampleSize(BigDecimal.valueOf(5)); inspection.setPassCount(BigDecimal.valueOf(2));
            inspection.setFailCount(BigDecimal.valueOf(3)); inspection.setPassRate(BigDecimal.valueOf(40));
            inspection.setResult("FAIL"); inspection.setCustomFields(null); em.persist(inspection); em.flush();
            return new Sample(factoryId, inspection.getId());
        });
    }
    private DecisionAuditLog application(Sample sample) {
        DecisionAuditLog log = DecisionAuditLog.builder().factoryId(sample.factory()).entityType("QualityInspection")
                .entityId(sample.inspection()).decisionType(DecisionAuditLog.DecisionType.APPROVAL)
                .decisionMade("CONDITIONAL_RELEASE").requiresApproval(true)
                .approvalStatus(DecisionAuditLog.ApprovalStatus.PENDING).build();
        return audits.saveAndFlush(log);
    }

    @Test void concurrentNewApplicationsHaveOneWinnerForTheSameBatch() throws Exception {
        Sample sample = sample(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> apply = () -> {
                start.await();
                try { return tx(() -> { QualityInspection qi = guard.lockInspection(sample.factory(), inspections.findById(sample.inspection()).orElseThrow());
                    guard.assertCanStart(qi); application(sample); return 200; }); }
                catch (BusinessException conflict) { return conflict.getCode(); }
            };
            var a = pool.submit(apply); var b = pool.submit(apply); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(tx(() -> audits.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("QualityInspection", sample.inspection()))).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = DecisionAuditLog.ApprovalStatus.class, names = {"APPROVED", "REJECTED"})
    void stalePendingEntitiesAreRefreshedForDoubleApprovalOrApproveVersusReject(DecisionAuditLog.ApprovalStatus secondDecision) throws Exception {
        Sample sample = sample(); String id = tx(() -> application(sample).getId());
        var loaded = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<DecisionAuditLog.ApprovalStatus, Callable<Integer>> attempt = decision -> () -> {
                try { return tx(() -> {
                    DecisionAuditLog stale = audits.findById(id).orElseThrow();
                    QualityInspection qi = inspections.findById(sample.inspection()).orElseThrow();
                    assertThat(stale.getApprovalStatus()).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
                    loaded.countDown(); try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Missing concurrent caller"); }
                    catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
                    guard.lockInspection(sample.factory(), qi); guard.refreshApproval(stale);
                    if (stale.getApprovalStatus() != DecisionAuditLog.ApprovalStatus.PENDING) throw new BusinessException(409, "已处理");
                    stale.setApprovalStatus(decision); audits.saveAndFlush(stale); return 200;
                }); } catch (BusinessException conflict) { return conflict.getCode(); }
            };
            var a = pool.submit(attempt.apply(DecisionAuditLog.ApprovalStatus.APPROVED));
            var b = pool.submit(attempt.apply(secondDecision));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(tx(() -> audits.findById(id).orElseThrow().getApprovalStatus()))
                .isIn(DecisionAuditLog.ApprovalStatus.APPROVED, DecisionAuditLog.ApprovalStatus.REJECTED);
    }

    @Test void failedExecutionRollsBackApprovalAndCanBeRetried() {
        Sample sample = sample(); String id = tx(() -> application(sample).getId());
        assertThatThrownBy(() -> tx(() -> {
            var qi = guard.lockInspection(sample.factory(), inspections.findById(sample.inspection()).orElseThrow());
            var log = audits.findById(id).orElseThrow(); guard.refreshApproval(log); guard.assertApprovalCurrent(qi);
            log.setApprovalStatus(DecisionAuditLog.ApprovalStatus.APPROVED); audits.saveAndFlush(log);
            throw new BusinessException(409, "Injected downstream failure");
        })).isInstanceOf(BusinessException.class);
        assertThat(tx(() -> audits.findById(id).orElseThrow().getApprovalStatus())).isEqualTo(DecisionAuditLog.ApprovalStatus.PENDING);
    }

    @Test void finalDispositionCannotBeReplacedByAnotherActionForTheSameInspection() {
        Sample sample = sample();
        tx(() -> { var approved = application(sample); approved.setApprovalStatus(DecisionAuditLog.ApprovalStatus.APPROVED); audits.saveAndFlush(approved); return null; });
        assertThatThrownBy(() -> tx(() -> { guard.assertCanStart(guard.lockInspection(sample.factory(), inspections.findById(sample.inspection()).orElseThrow())); return null; }))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最终处置");
        assertThatThrownBy(() -> tx(() -> { guard.assertApprovalCurrent(guard.lockInspection(sample.factory(), inspections.findById(sample.inspection()).orElseThrow())); return null; }))
                .isInstanceOf(BusinessException.class).hasMessageContaining("最终处置");
    }

    @Test void tenantMismatchIsRejectedBeforeTakingAnotherFactoryBatchLock() {
        Sample sample = sample();
        assertThatThrownBy(() -> tx(() -> { guard.lockInspection("OTHER", inspections.findById(sample.inspection()).orElseThrow()); return null; }))
                .isInstanceOf(BusinessException.class).hasMessageContaining("当前工厂");
    }

    @Test void olderPassingInspectionCannotStartReleaseAfterNewerFailure() {
        Sample sample = sample();
        tx(() -> {
            var original = inspections.findById(sample.inspection()).orElseThrow();
            original.setResult("PASS"); original.setPassRate(BigDecimal.valueOf(100));
            original.setPassCount(BigDecimal.valueOf(5)); original.setFailCount(BigDecimal.ZERO);
            QualityInspection newer = new QualityInspection(); newer.setId(UUID.randomUUID().toString());
            newer.setFactoryId(original.getFactoryId()); newer.setProductionBatchId(original.getProductionBatchId());
            newer.setInspectorId(original.getInspectorId()); newer.setInspectionDate(original.getInspectionDate());
            newer.setSampleSize(BigDecimal.valueOf(5)); newer.setPassCount(BigDecimal.valueOf(2));
            newer.setFailCount(BigDecimal.valueOf(3)); newer.setPassRate(BigDecimal.valueOf(40));
            newer.setResult("FAIL"); newer.setCustomFields(null); em.persist(newer); em.flush();
            newer.setCreatedAt(original.getCreatedAt().plusSeconds(1)); em.flush();
            return null;
        });
        assertThatThrownBy(() -> tx(() -> {
            var passing = guard.lockInspection(sample.factory(), inspections.findById(sample.inspection()).orElseThrow());
            assertThat(passing.getResult()).isEqualTo("PASS");
            guard.assertCanStart(passing); return null;
        })).isInstanceOf(BusinessException.class).hasMessageContaining("更新的质检");
    }
}
