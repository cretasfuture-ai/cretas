package com.cretas.aims.service.consent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.learning.ActiveLearningSample;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收训练样本时保密名单查不出来 (查库失败): 只丢这一条样本, 不抛给调用方 (2026-10-29)。
 *
 * <p>用真实的 {@link ProcessSecretSampleFilter} (只把它底下的保密策略查询换成会抛的桩),
 * 经真实的 {@code recordSample} / {@code collectSample} 入口跑。三组读数:
 * <ul>
 *   <li>查库抛错 → 不抛、不存、WARN 日志带工厂和原因 (不带原话)、计数 +1</li>
 *   <li>查库正常、没提到保密产品 → 照常存 (阴性对照: 收样本没被一起关掉)</li>
 *   <li>查库正常、提到保密产品 → 仍然不存 (保密没被放松)</li>
 * </ul>
 * 训练样本表没有状态列、低置信样本表的状态里没有「待复判」, 所以判断不了的样本不存 (见 filter 注释)。
 */
class TrainingSampleSecrecyLookupFailureContractTest {

    static final String FACTORY = "F006";
    static final String PROTECTED_NAME = "秘制牛肉干";
    /** 原话: 不提保密产品, 用来区分「因查库失败没存」与「因提到保密产品没存」 */
    static final String PLAIN_TEXT = "今天包装车间做了多少箱";
    static final String PROTECTED_TEXT = "秘制牛肉干今天做了多少箱";

    final ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
    final ProductTypeRepository products = mock(ProductTypeRepository.class);
    final ProcessSecretSampleFilter filter = new ProcessSecretSampleFilter(access, products);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger filterLogger = (Logger) LoggerFactory.getLogger(ProcessSecretSampleFilter.class);

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(filter, "meterRegistry", meters);
        logs.start();
        filterLogger.addAppender(logs);
    }

    @AfterEach
    void unwire() {
        filterLogger.detachAppender(logs);
    }

    void lookupFails() {
        when(access.protectedProductTypeIds(FACTORY))
                .thenThrow(new DataAccessResourceFailureException("connection reset"));
    }

    void lookupWorks() {
        ProductType p = new ProductType();
        p.setId("P1");
        p.setFactoryId(FACTORY);
        p.setName(PROTECTED_NAME);
        when(access.protectedProductTypeIds(FACTORY)).thenReturn(Set.of("P1"));
        when(products.findAllById(any())).thenReturn(List.of(p));
    }

    double unverifiedCount(String kind) {
        Counter c = meters.find(ProcessSecretSampleFilter.METRIC_SAMPLE_UNVERIFIED).tag("kind", kind).counter();
        return c == null ? 0.0 : c.count();
    }

    List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    void assertFailureLoggedWithoutRawText(String kind, String rawText) {
        assertThat(unverifiedCount(kind)).as("计数 +1").isEqualTo(1.0);
        assertThat(warnings()).hasSize(1);
        String msg = warnings().get(0).getFormattedMessage();
        assertThat(msg).contains("factory=" + FACTORY).contains("DataAccessResourceFailureException")
                .contains("kind=" + kind);
        assertThat(msg).as("日志不带原话").doesNotContain(rawText);
    }

    @Nested
    class Verdict {
        @Test
        void lookupFailureIsUnverifiedNotAnException() {
            lookupFails();
            assertThatCode(() -> filter.checkTrainingSample("intent", FACTORY, PLAIN_TEXT)).doesNotThrowAnyException();
            assertThat(unverifiedCount("intent")).isEqualTo(1.0); // 上面那次
            assertThat(filter.checkTrainingSample("intent", FACTORY, PLAIN_TEXT))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.UNVERIFIED);
        }

        @Test
        void lookupWorksGivesClearOrProtected() {
            lookupWorks();
            assertThat(filter.checkTrainingSample("intent", FACTORY, PLAIN_TEXT))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.CLEAR);
            assertThat(filter.checkTrainingSample("intent", FACTORY, PROTECTED_TEXT))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
            assertThat(unverifiedCount("intent")).isZero();
            assertThat(warnings()).isEmpty();
        }

        @Test
        void otherCallersStillSeeTheException() {
            // 外发出口靠「查不出来就抛」来拒绝外发: 只有收样本这一个入口吞异常, 原方法不变
            lookupFails();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> filter.mentionsProtectedProduct(FACTORY, PLAIN_TEXT))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }
    }

    @Nested
    class IntentSamples {
        final TrainingSampleRepository sampleRepository = mock(TrainingSampleRepository.class);
        final TrainingConsentService consent = mock(TrainingConsentService.class);
        final ExpressionLearningServiceImpl service = new ExpressionLearningServiceImpl(
                null, sampleRepository, null, null, null, consent, filter);

        @BeforeEach
        void consentGiven() {
            when(consent.status(FACTORY)).thenReturn(TrainingConsentService.Status.AUTHORIZED);
        }

        @Test
        void lookupFailureDropsOnlyThisSampleAndDoesNotThrow() {
            lookupFails();
            assertThatCode(() -> assertThat(service.recordSample(FACTORY, PLAIN_TEXT, "PRODUCTION_QUERY",
                    TrainingSample.MatchMethod.EXACT, 1.0, null)).isNull())
                    .doesNotThrowAnyException();
            verify(sampleRepository, never()).save(any());
            assertFailureLoggedWithoutRawText("intent", PLAIN_TEXT);
        }

        @Test
        void lookupWorksAndSampleIsRecorded() {
            lookupWorks();
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            TrainingSample saved = service.recordSample(FACTORY, PLAIN_TEXT, "PRODUCTION_QUERY",
                    TrainingSample.MatchMethod.EXACT, 1.0, null);
            assertThat(saved).isNotNull();
            assertThat(saved.getUserInput()).isEqualTo(PLAIN_TEXT);
            verify(sampleRepository).save(any());
            assertThat(unverifiedCount("intent")).isZero();
        }

        @Test
        void protectedNameIsStillExcluded() {
            lookupWorks();
            assertThat(service.recordSample(FACTORY, PROTECTED_TEXT, "PRODUCTION_QUERY",
                    TrainingSample.MatchMethod.EXACT, 1.0, null)).isNull();
            verify(sampleRepository, never()).save(any());
        }
    }

    @Nested
    class ActiveLearningSamples {
        final ActiveLearningSampleRepository sampleRepository = mock(ActiveLearningSampleRepository.class);
        final TrainingConsentService consent = mock(TrainingConsentService.class);
        final ActiveLearningServiceImpl service = new ActiveLearningServiceImpl(
                sampleRepository, null, null, null, null, null, null, null, null,
                consent, filter, null, null, null, null);

        @BeforeEach
        void setUp() {
            ReflectionTestUtils.setField(service, "confidenceThreshold", new BigDecimal("0.7"));
            when(consent.status(FACTORY)).thenReturn(TrainingConsentService.Status.AUTHORIZED);
        }

        ActiveLearningSample collect(String text) {
            return service.collectSample(FACTORY, 1L, text, text, "X", new BigDecimal("0.3"),
                    "SEMANTIC", null, null, "rec-1");
        }

        @Test
        void lookupFailureDropsOnlyThisSampleAndDoesNotThrow() {
            lookupFails();
            assertThatCode(() -> assertThat(collect(PLAIN_TEXT)).isNull()).doesNotThrowAnyException();
            verify(sampleRepository, never()).save(any());
            assertFailureLoggedWithoutRawText("active_learning", PLAIN_TEXT);
        }

        @Test
        void lookupWorksAndSampleIsCollected() {
            lookupWorks();
            when(sampleRepository.existsBySourceRecordId(anyString())).thenReturn(false);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            ActiveLearningSample s = collect(PLAIN_TEXT);
            assertThat(s).isNotNull();
            assertThat(s.getLearningStatus()).isEqualTo(ActiveLearningSample.LearningStatus.PENDING);
            verify(sampleRepository).save(any());
        }

        @Test
        void protectedNameIsStillExcluded() {
            lookupWorks();
            assertThat(collect(PROTECTED_TEXT)).isNull();
            verify(sampleRepository, never()).save(any());
        }
    }
}
