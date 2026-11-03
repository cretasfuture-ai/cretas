package com.cretas.aims.service.consent;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.ActiveLearningService;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.FactoryConfigService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.KeywordLearningService;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.impl.KeywordLearningServiceImpl;
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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-29 过滤规则待办第 8 / 10 条, 在<b>真实的 Spring 事务代理</b>下 (H2 + {@link DataSourceTransactionManager}):
 *
 * <ul>
 *   <li>第 8 条: 学习前的名单查询失败, 不许让 {@code @Transactional} 的学习方法在提交时抛
 *       {@code UnexpectedRollbackException}。以前 {@code LearningContentRules.factoryLearnable} /
 *       {@code KeywordLearningServiceImpl.manualKeywordAllowed} / 全局种子批写在事务<b>里</b> catch 名单查询的异常 ——
 *       仓库层已把外层事务标成只能回滚, 接住也没用。现在都走过滤器挂起外层事务的方法。</li>
 *   <li>第 10 条: 收样本 ({@code recordSample} / {@code collectSample}) 不开事务, 名单在写入之前判完 ——
 *       以前一次收样本 (名单缓存未命中) 同时占两个连接。</li>
 * </ul>
 * 名单查询桩挂 {@code @Transactional(readOnly = true)}, 与生产上 Spring Data 仓库的事务形状一致。
 */
class LearningSecrecyCheckOutsideTransactionContractTest {

    static final String FACTORY = "F006";

    /** 名单查询: 带只读事务; fail=true 时查库失败抛 DataAccessException。记录查询时同时打开的连接数。 */
    static class AccessService extends ProcessSecretAccessService {
        final AtomicBoolean fail = new AtomicBoolean(true);
        final AtomicInteger openAtLookup = new AtomicInteger(-1);
        CountingDataSource ds;

        AccessService() {
            super(null, null, null);
        }

        @Override
        @Transactional(readOnly = true)
        public Set<String> protectedProductTypeIds(String factoryId) {
            openAtLookup.set(ds.open.get());
            if (fail.get()) {
                throw new DataAccessResourceFailureException("connection reset");
            }
            return Set.of();
        }

        @Override
        @Transactional(readOnly = true)
        public Set<String> factoriesWithProtectedProducts() {
            openAtLookup.set(ds.open.get());
            if (fail.get()) {
                throw new DataAccessResourceFailureException("connection reset");
            }
            return Set.of();
        }
    }

    /** 数同时打开的连接 (open / 最大值)。 */
    static class CountingDataSource extends DelegatingDataSource {
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            Connection real = super.getConnection();
            max.accumulateAndGet(open.incrementAndGet(), Math::max);
            InvocationHandler h = (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    open.decrementAndGet();
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Rig {
        @Bean
        CountingDataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:learn_tx_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return new CountingDataSource(ds);
        }

        @Bean
        PlatformTransactionManager transactionManager(CountingDataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        AccessService accessService(CountingDataSource ds) {
            AccessService a = new AccessService();
            a.ds = ds;
            return a;
        }

        @Bean
        ProcessSecretSampleFilter processSecretSampleFilter(AccessService access) {
            return new ProcessSecretSampleFilter(access, mock(ProductTypeRepository.class),
                    mock(FactoryNameDirectoryRepository.class));
        }

        @Bean
        TrainingConsentService trainingConsentService() {
            TrainingConsentService consent = mock(TrainingConsentService.class);
            when(consent.status(anyString())).thenReturn(TrainingConsentService.Status.AUTHORIZED);
            return consent;
        }

        @Bean
        AIIntentConfigRepository intents() {
            AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
            AIIntentConfig own = new AIIntentConfig();
            own.setIntentCode("PRODUCTION_QUERY");
            own.setFactoryId(FACTORY);
            own.setKeywords("[\"产量\"]");
            own.setIsActive(true);
            when(intents.findByFactoryIdAndIntentCode(FACTORY, "PRODUCTION_QUERY")).thenReturn(Optional.of(own));
            return intents;
        }

        @Bean
        KeywordLearningServiceImpl keywordLearningService(AIIntentConfigRepository intents, TrainingConsentService consent,
                                                          ProcessSecretSampleFilter filter) {
            FactoryConfigService factoryConfig = mock(FactoryConfigService.class);
            when(factoryConfig.isAutoLearnEnabled(anyString())).thenReturn(true);
            when(factoryConfig.getMaxKeywordsPerIntent(anyString())).thenReturn(50);
            when(factoryConfig.getLlmNewKeywordWeight(anyString())).thenReturn(BigDecimal.ONE);
            return new KeywordLearningServiceImpl(intents, mock(KeywordEffectivenessService.class), factoryConfig,
                    new com.fasterxml.jackson.databind.ObjectMapper(), consent, filter);
        }

        @Bean
        TrainingSampleHoldStore trainingSampleHoldStore() {
            return mock(TrainingSampleHoldStore.class);
        }

        @Bean
        LearnedExpressionRepository learnedExpressionRepository() {
            return mock(LearnedExpressionRepository.class);
        }

        @Bean
        TrainingSampleRepository trainingSampleRepository() {
            TrainingSampleRepository r = mock(TrainingSampleRepository.class);
            when(r.save(any())).thenAnswer(i -> i.getArgument(0));
            return r;
        }

        @Bean
        ActiveLearningSampleRepository activeLearningSampleRepository() {
            ActiveLearningSampleRepository r = mock(ActiveLearningSampleRepository.class);
            when(r.save(any())).thenAnswer(i -> i.getArgument(0));
            return r;
        }

        @Bean
        ExpressionLearningServiceImpl expressionLearningService(LearnedExpressionRepository expressions,
                                                                TrainingSampleRepository samples,
                                                                TrainingConsentService consent,
                                                                ProcessSecretSampleFilter filter) {
            return new ExpressionLearningServiceImpl(expressions, samples, null, null, null, consent, filter);
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
    AccessService access;
    CountingDataSource ds;

    @BeforeEach
    void start() {
        ctx = new AnnotationConfigApplicationContext(Rig.class);
        // the bean is a CGLIB transaction proxy: its own fields are not the target's
        access = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(ctx.getBean(AccessService.class));
        ds = ctx.getBean(CountingDataSource.class);
        // 仪器自检: 被测 bean 都真的套着事务代理, 否则量不到它要量的那一层
        assertThat(AopUtils.isAopProxy(ctx.getBean(KeywordLearningService.class))).isTrue();
        assertThat(AopUtils.isAopProxy(ctx.getBean(ExpressionLearningService.class))).isTrue();
        assertThat(AopUtils.isAopProxy(ctx.getBean(ActiveLearningService.class))).isTrue();
        assertThat(AopUtils.isAopProxy(ctx.getBean(AccessService.class))).isTrue();
        assertThat(AopUtils.isAopProxy(ctx.getBean(ProcessSecretSampleFilter.class))).isTrue();
    }

    @AfterEach
    void stop() {
        ctx.close();
    }

    // ---------------- 第 8 条: 名单查询失败不把学习方法的事务弄成只能回滚 ----------------

    @Test
    void learnKeywordsSurvivesALookupFailure() {
        KeywordLearningService service = ctx.getBean(KeywordLearningService.class);
        assertThatCode(() -> assertThat(service.learnKeywords(FACTORY, "PRODUCTION_QUERY", "今天出货情况怎么样",
                KeywordLearningService.LearnSource.AUTO_LEARN)).isZero())
                .doesNotThrowAnyException();
        assertThat(access.openAtLookup.get()).as("the lookup really ran (and failed)").isGreaterThanOrEqualTo(1);
    }

    @Test
    void manualKeywordSurvivesALookupFailure() {
        KeywordLearningService service = ctx.getBean(KeywordLearningService.class);
        assertThatCode(() -> assertThat(service.addKeywordsToIntent(FACTORY, "PRODUCTION_QUERY", List.of("出货情况"),
                KeywordLearningService.LearnSource.MANUAL_ADD)).isZero())
                .doesNotThrowAnyException();
        assertThat(access.openAtLookup.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void manualKeywordIsAddedWhenTheLookupWorks() {
        access.fail.set(false);
        KeywordLearningService service = ctx.getBean(KeywordLearningService.class);
        assertThat(service.addKeywordsToIntent(FACTORY, "PRODUCTION_QUERY", List.of("出货情况"),
                KeywordLearningService.LearnSource.MANUAL_ADD)).isEqualTo(1);       // 阳性对照: 这条路能写
    }

    @Test
    void factoryExpressionSurvivesALookupFailure() {
        ExpressionLearningService service = ctx.getBean(ExpressionLearningService.class);
        assertThatCode(() -> assertThat(service.learnExpression(FACTORY, "PRODUCTION_QUERY", "今天出货情况怎么样",
                0.95, LearnedExpression.SourceType.USER_FEEDBACK)).isNull())
                .doesNotThrowAnyException();
        assertThat(access.openAtLookup.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void globalSeedBatchSurvivesALookupFailureAndJudgesEachItem() {
        ExpressionLearningService service = ctx.getBean(ExpressionLearningService.class);
        assertThatCode(() -> assertThat(service.learnExpressions("*", "PRODUCTION_QUERY",
                List.of("今天出货情况", "本月产量多少"), 1.0, LearnedExpression.SourceType.MANUAL)).isZero())
                .doesNotThrowAnyException();
        verify(ctx.getBean(LearnedExpressionRepository.class), never()).save(any());
    }

    @Test
    void globalSeedBatchIsWrittenWhenTheLookupWorks() {
        access.fail.set(false);
        LearnedExpressionRepository expressions = ctx.getBean(LearnedExpressionRepository.class);
        when(expressions.findOneByHashAndFactory(anyString(), anyString())).thenReturn(Optional.empty());
        when(expressions.save(any())).thenAnswer(i -> i.getArgument(0));
        ExpressionLearningService service = ctx.getBean(ExpressionLearningService.class);
        assertThat(service.learnExpressions("*", "PRODUCTION_QUERY", List.of("今天出货情况", "本月产量多少"),
                1.0, LearnedExpression.SourceType.MANUAL)).isEqualTo(2);           // 阳性对照
    }

    // ---------------- 第 10 条: 收样本时名单查询不再与一个打开的事务并存 ----------------

    @Test
    void recordSampleHoldsOneConnectionAtATime() {
        access.fail.set(false);
        ExpressionLearningService service = ctx.getBean(ExpressionLearningService.class);
        TrainingSample saved = service.recordSample(FACTORY, "今天包装车间做了多少箱", "PRODUCTION_QUERY",
                TrainingSample.MatchMethod.EXACT, 1.0, null);
        assertThat(saved).as("positive control: the sample is stored").isNotNull();
        assertThat(access.openAtLookup.get()).as("connections open while the lists are looked up").isEqualTo(1);
        assertThat(ds.max.get()).isEqualTo(1);
    }

    @Test
    void collectSampleHoldsOneConnectionAtATime() {
        access.fail.set(false);
        ActiveLearningService service = ctx.getBean(ActiveLearningService.class);
        assertThat(service.collectSample(FACTORY, 1L, "今天包装车间做了多少箱", null, "X",
                new BigDecimal("0.3"), "SEMANTIC", null, null, "rec-1")).isNotNull();
        assertThat(access.openAtLookup.get()).isEqualTo(1);
        assertThat(ds.max.get()).isEqualTo(1);
    }
}
