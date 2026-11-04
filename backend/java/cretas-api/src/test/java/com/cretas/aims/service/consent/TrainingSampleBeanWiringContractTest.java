package com.cretas.aims.service.consent;

import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleSecrecyHoldRepository;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionService;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 本 PR 新增 / 改了注入方式的 bean 由 <b>Spring 自己</b> 实例化 (不是手工 new) —— 2026-11-03 CI 抓到:
 * {@code TrainingSampleHoldRejudger} 有两个构造器却没标 {@code @Autowired}, Spring 去找无参构造器,
 * 整个应用起不来 (Fresh DB progress 与 fast-contracts 里 48 个上下文加载失败)。单元测试手工 new, 从来没走到这一步。
 *
 * <p>这里按生产的写法注册真实的类 (依赖用 mock 顶上), 让 Spring 走构造器解析、{@code @Value} 默认值、
 * {@code @Lazy}、必需的字段注入 ({@code holdStore})。
 */
class TrainingSampleBeanWiringContractTest {

    @SuppressWarnings("unchecked")
    private static <T> java.util.function.Supplier<T> mocked(Class<T> type) {
        return () -> mock(type);
    }

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("conversionService", ConversionService.class, ApplicationConversionService::getSharedInstance)
            // real dependencies of the beans under test, mocked
            .withBean(TrainingSampleSecrecyHoldRepository.class, mocked(TrainingSampleSecrecyHoldRepository.class))
            .withBean(ProcessSecretAccessService.class, mocked(ProcessSecretAccessService.class))
            .withBean(ProductTypeRepository.class, mocked(ProductTypeRepository.class))
            .withBean(FactoryNameDirectoryRepository.class, mocked(FactoryNameDirectoryRepository.class))
            .withBean(TrainingConsentService.class, mocked(TrainingConsentService.class))
            .withBean(LearnedExpressionRepository.class, mocked(LearnedExpressionRepository.class))
            .withBean(TrainingSampleRepository.class, mocked(TrainingSampleRepository.class))
            .withBean(com.cretas.aims.service.EmbeddingClient.class, mocked(com.cretas.aims.service.EmbeddingClient.class))
            .withBean(com.cretas.aims.service.RequestScopedEmbeddingCache.class,
                    mocked(com.cretas.aims.service.RequestScopedEmbeddingCache.class))
            .withBean(com.cretas.aims.service.IntentEmbeddingCacheService.class,
                    mocked(com.cretas.aims.service.IntentEmbeddingCacheService.class))
            .withBean(ActiveLearningSampleRepository.class, mocked(ActiveLearningSampleRepository.class))
            .withBean(com.cretas.aims.repository.learning.IntentTransitionMatrixRepository.class,
                    mocked(com.cretas.aims.repository.learning.IntentTransitionMatrixRepository.class))
            .withBean(com.cretas.aims.repository.learning.CrossFactoryKnowledgeRepository.class,
                    mocked(com.cretas.aims.repository.learning.CrossFactoryKnowledgeRepository.class))
            .withBean(com.cretas.aims.repository.learning.CrossFactoryKnowledgeAdoptionRepository.class,
                    mocked(com.cretas.aims.repository.learning.CrossFactoryKnowledgeAdoptionRepository.class))
            .withBean(com.cretas.aims.repository.learning.LearningTaskRepository.class,
                    mocked(com.cretas.aims.repository.learning.LearningTaskRepository.class))
            .withBean(com.cretas.aims.repository.learning.ModelPerformanceLogRepository.class,
                    mocked(com.cretas.aims.repository.learning.ModelPerformanceLogRepository.class))
            .withBean(com.cretas.aims.repository.learning.AnnotationQueueRepository.class,
                    mocked(com.cretas.aims.repository.learning.AnnotationQueueRepository.class))
            .withBean(com.cretas.aims.repository.learning.LearningSuggestionRepository.class,
                    mocked(com.cretas.aims.repository.learning.LearningSuggestionRepository.class))
            .withBean(com.cretas.aims.repository.learning.SampleClusterRepository.class,
                    mocked(com.cretas.aims.repository.learning.SampleClusterRepository.class))
            .withBean(com.cretas.aims.repository.IntentMatchRecordRepository.class,
                    mocked(com.cretas.aims.repository.IntentMatchRecordRepository.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(IntentKnowledgeBase.class, mocked(IntentKnowledgeBase.class))
            // the classes under test, instantiated by Spring exactly as in production
            .withBean(ProcessSecretSampleFilter.class)
            .withBean(TrainingSampleHoldStore.class)
            .withBean(TrainingSampleHoldRejudger.class)
            .withBean(ExpressionLearningServiceImpl.class)
            .withBean(ActiveLearningServiceImpl.class);

    @Test
    void springCanInstantiateEveryNewOrRewiredBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TrainingSampleHoldRejudger.class);
            assertThat(ctx).hasSingleBean(TrainingSampleHoldStore.class);
            assertThat(ctx).hasSingleBean(ProcessSecretSampleFilter.class);
            // the required hold-store field is really injected (not null as in hand-made test instances)
            Object expression = AopTestUtils.getUltimateTargetObject(ctx.getBean(ExpressionLearningServiceImpl.class));
            Object active = AopTestUtils.getUltimateTargetObject(ctx.getBean(ActiveLearningServiceImpl.class));
            assertThat(ReflectionTestUtils.getField(expression, "holdStore")).isSameAs(ctx.getBean(TrainingSampleHoldStore.class));
            assertThat(ReflectionTestUtils.getField(active, "holdStore")).isSameAs(ctx.getBean(TrainingSampleHoldStore.class));
            // @Value default resolved through the real constructor
            assertThat(ReflectionTestUtils.getField(ctx.getBean(TrainingSampleHoldRejudger.class), "maxAge"))
                    .isEqualTo(java.time.Duration.ofDays(7));
        });
    }
}
