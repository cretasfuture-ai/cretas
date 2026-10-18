package com.cretas.aims.repository.indicator;

import com.cretas.aims.dto.platform.CreateFactoryRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.repository.DroolsRuleRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.impl.FactoryServiceImpl;
import com.cretas.aims.service.indicator.FactoryIndicatorProvisioner;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import com.cretas.aims.service.restaurant.RestaurantAgentActionWorkflowProvisioner;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real Hibernate repositories and Spring transaction proxies, including rollback and concurrent retries. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import({FactoryIndicatorProvisioner.class, FactoryServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FactoryIndicatorRepositoryQueryValidationTest {
    @Autowired FactoryRepository factories;
    @Autowired IndicatorRepository indicators;
    @Autowired FactoryIndicatorProvisioner provisioner;
    @Autowired FactoryServiceImpl factoryService;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @MockBean DroolsRuleRepository rules;
    @MockBean RestaurantAgentActionWorkflowProvisioner restaurant;
    @MockBean IndicatorComputationStrategyRegistry registry;

    @BeforeEach void strategies() {
        when(registry.find(anyString())).thenReturn(Optional.of(mock(IndicatorComputationStrategy.class)));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private String create(String name) {
        return factoryService.createFactory(CreateFactoryRequest.builder().name(name)
                .industryCode("PTEST").regionCode("3101").build()).getId();
    }

    private long count(String table, String factoryId) {
        return inTransaction(() -> ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE factory_id = :factory")
                .setParameter("factory", factoryId).getSingleResult()).longValue());
    }

    @Test void nativeExistenceQueryIncludesDeletedButNeverAnotherFactory() {
        String code = "FACTORY_ACTUAL_TOTAL_COST";
        String factoryId = "P14-QUERY-" + UUID.randomUUID();
        inTransaction(() -> {
            Indicator item = new Indicator(); item.setFactoryId(factoryId); item.setCode(code);
            item.setName("保留删除决定"); item.setCategory("FACTORY"); item.setConfig(null);
            item.setDeletedAt(LocalDateTime.now()); indicators.saveAndFlush(item);
            assertThat(indicators.findByFactoryIdAndCode(factoryId, code)).isEmpty();
            assertThat(indicators.existsIncludingDeleted(factoryId, code)).isTrue();
            assertThat(indicators.existsIncludingDeleted("ANOTHER", code)).isFalse();
            assertThat(indicators.existsIncludingDeleted(factoryId, "UNKNOWN")).isFalse();
            return null;
        });
    }

    @Test void lateStrategyFailureRollsBackFactoryDefinitionsAndComputations() {
        String name = "P14真实事务失败-" + UUID.randomUUID();
        long beforeDefinitions = indicators.count();
        long beforeSources = inTransaction(() -> ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM indicator_computations").getSingleResult()).longValue());
        when(registry.find("FACTORY_ACTUAL_TOTAL_COST")).thenThrow(new IllegalStateException("late-failure"));
        assertThatThrownBy(() -> create(name)).isInstanceOf(IllegalStateException.class).hasMessage("late-failure");
        assertThat(factories.findByName(name)).isEmpty();
        assertThat(indicators.count()).isEqualTo(beforeDefinitions);
        assertThat(inTransaction(() -> ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM indicator_computations").getSingleResult()).longValue()))
                .isEqualTo(beforeSources);
    }

    @Test void mandatoryRejectsCallsOutsideAFormalTransaction() {
        assertThatThrownBy(() -> provisioner.provision("NO-TRANSACTION"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void realJpaMappingPreservesTheFullCostRecalculationAuditAction() {
        inTransaction(() -> {
            OperationLog log = new OperationLog();
            log.setFactoryId("P14-AUDIT"); log.setModule("PRODUCTION_PLAN");
            log.setAction("RECALCULATE_SETTLED_COSTS"); log.setSuccess(true);
            entityManager.persist(log); entityManager.flush();
            assertThat(entityManager.createNativeQuery("SELECT action FROM operation_logs WHERE id=:id")
                    .setParameter("id", log.getId()).getSingleResult()).isEqualTo("RECALCULATE_SETTLED_COSTS");
            return null;
        });
    }

    @Test void retryKeepsInactiveAndDeletedChoicesWithoutDuplicateSources() {
        String factoryId = create("P14保留选择-" + UUID.randomUUID());
        inTransaction(() -> {
            entityManager.createNativeQuery("UPDATE indicators SET is_active=false, name='本厂成本' "
                    + "WHERE factory_id=:factory AND code='FACTORY_LU_UNIT_COST'")
                    .setParameter("factory", factoryId).executeUpdate();
            entityManager.createNativeQuery("UPDATE indicators SET deleted_at=CURRENT_TIMESTAMP "
                    + "WHERE factory_id=:factory AND code='FACTORY_ACTUAL_TOTAL_COST'")
                    .setParameter("factory", factoryId).executeUpdate();
            return null;
        });
        assertThat(inTransaction(() -> provisioner.provision(factoryId))).isZero();
        assertThat(count("indicators", factoryId)).isEqualTo(4);
        assertThat(inTransaction(() -> ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM indicator_computations c JOIN indicators i ON i.id=c.indicator_id "
                        + "WHERE i.factory_id=:factory").setParameter("factory", factoryId).getSingleResult()).longValue()))
                .isEqualTo(4);
        assertThat(inTransaction(() -> entityManager.createNativeQuery(
                "SELECT name FROM indicators WHERE factory_id=:factory AND code='FACTORY_LU_UNIT_COST' AND is_active=false")
                .setParameter("factory", factoryId).getSingleResult())).isEqualTo("本厂成本");
    }

    @Test void factoryLockSerializesConcurrentProvisioning() throws Exception {
        Factory factory = new Factory(); factory.setId("P14-CON-" + UUID.randomUUID());
        factory.setName(factory.getId()); factories.saveAndFlush(factory);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> task = () -> {
                ready.countDown(); assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return inTransaction(() -> provisioner.provision(factory.getId()));
            };
            Future<Integer> first = workers.submit(task), second = workers.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(4, 0);
            assertThat(count("indicators", factory.getId())).isEqualTo(4);
        } finally { start.countDown(); workers.shutdownNow(); }
    }
}
