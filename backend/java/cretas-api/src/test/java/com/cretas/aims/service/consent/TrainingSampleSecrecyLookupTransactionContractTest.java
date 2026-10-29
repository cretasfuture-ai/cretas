package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.ActiveLearningService;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实事务代理下, 保密名单查库失败也不能从收样本入口抛出去。
 *
 * <p>只在方法体里 catch 不够: 名单查询经过仓库层的事务代理, 它在调用方事务里出错时会把整个事务标成
 * 「只能回滚」, 调用方接住异常照常返回, 提交时仍抛 {@code UnexpectedRollbackException} —— 识别请求照样失败。
 * 本测试用真实的 Spring 事务基础设施 (H2 + {@link DataSourceTransactionManager} + 注解事务代理) 复现这一层:
 * 名单查询桩挂 {@code @Transactional(readOnly = true)} 并抛 {@link DataAccessResourceFailureException},
 * 与生产上 Spring Data 仓库的形状一致。
 */
class TrainingSampleSecrecyLookupTransactionContractTest {

    /** 形状同生产: 带只读事务的查询, 查库失败抛 DataAccessException */
    static class FailingAccessService extends ProcessSecretAccessService {
        FailingAccessService() {
            super(null, null, null);
        }

        @Override
        @Transactional(readOnly = true)
        public Set<String> protectedProductTypeIds(String factoryId) {
            throw new DataAccessResourceFailureException("connection reset");
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Rig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:sample_tx_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        FailingAccessService accessService() {
            return new FailingAccessService();
        }

        @Bean
        ProcessSecretSampleFilter processSecretSampleFilter(FailingAccessService access) {
            return new ProcessSecretSampleFilter(access, mock(ProductTypeRepository.class));
        }

        @Bean
        TrainingConsentService trainingConsentService() {
            TrainingConsentService consent = mock(TrainingConsentService.class);
            when(consent.status("F006")).thenReturn(TrainingConsentService.Status.AUTHORIZED);
            return consent;
        }

        @Bean
        TrainingSampleRepository trainingSampleRepository() {
            return mock(TrainingSampleRepository.class);
        }

        @Bean
        ActiveLearningSampleRepository activeLearningSampleRepository() {
            return mock(ActiveLearningSampleRepository.class);
        }

        @Bean
        ExpressionLearningServiceImpl expressionLearningService(TrainingSampleRepository samples,
                                                                TrainingConsentService consent,
                                                                ProcessSecretSampleFilter filter) {
            return new ExpressionLearningServiceImpl(null, samples, null, null, null, consent, filter);
        }

        @Bean
        ActiveLearningServiceImpl activeLearningService(ActiveLearningSampleRepository samples,
                                                        TrainingConsentService consent,
                                                        ProcessSecretSampleFilter filter) {
            ActiveLearningServiceImpl s = new ActiveLearningServiceImpl(
                    samples, null, null, null, null, null, null, null, null,
                    consent, filter, null, null, null, null);
            ReflectionTestUtils.setField(s, "confidenceThreshold", new BigDecimal("0.7"));
            return s;
        }
    }

    AnnotationConfigApplicationContext ctx;

    @BeforeEach
    void start() {
        ctx = new AnnotationConfigApplicationContext(Rig.class);
        // 仪器自检: 三个被测 bean 都真的套着事务代理, 否则本测试量不到它要量的那一层
        assertThat(AopUtils.isAopProxy(ctx.getBean(ExpressionLearningService.class))).isTrue();
        assertThat(AopUtils.isAopProxy(ctx.getBean(ActiveLearningService.class))).isTrue();
        assertThat(AopUtils.isAopProxy(ctx.getBean(FailingAccessService.class))).isTrue();
    }

    @AfterEach
    void stop() {
        ctx.close();
    }

    @Test
    void recordSampleDoesNotThrowThroughTheTransactionProxy() {
        ExpressionLearningService service = ctx.getBean(ExpressionLearningService.class);
        assertThatCode(() -> assertThat(service.recordSample("F006", "今天包装车间做了多少箱", "PRODUCTION_QUERY",
                TrainingSample.MatchMethod.EXACT, 1.0, null)).isNull())
                .doesNotThrowAnyException();
        verify(ctx.getBean(TrainingSampleRepository.class), never()).save(any());
    }

    @Test
    void collectSampleDoesNotThrowThroughTheTransactionProxy() {
        ActiveLearningService service = ctx.getBean(ActiveLearningService.class);
        assertThatCode(() -> assertThat(service.collectSample("F006", 1L, "今天包装车间做了多少箱", null, "X",
                new BigDecimal("0.3"), "SEMANTIC", null, null, "rec-1")).isNull())
                .doesNotThrowAnyException();
        verify(ctx.getBean(ActiveLearningSampleRepository.class), never()).save(any());
    }
}
