package com.cretas.aims.service.consent;

import com.cretas.aims.ai.synthetic.SyntheticDataService;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.event.BatchCompletedEvent;
import com.cretas.aims.repository.TrainingDataRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.ProductionDataCollectorService;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Owner 规则 (2026-10-22): 工厂真实数据未经授权不进任何训练集。Java 侧四个写入口
 * (意图训练样本 / 主动学习样本 / 排产训练数据 / 由真实样本派生的合成样本) 写之前都问
 * {@link TrainingConsentService}; 授权读自 SmartBI 库 training_consent, 读不到按未授权。
 */
class TrainingConsentGateContractTest {

    static DataSource consentDb(String... rows) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:consent_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE training_consent (factory_id VARCHAR(64) PRIMARY KEY, consent_scope VARCHAR(32))");
        for (String row : rows) {
            String[] kv = row.split("=");
            jdbc.update("INSERT INTO training_consent VALUES (?, ?)", kv[0], kv[1]);
        }
        return ds;
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<DataSource> provider(DataSource ds) {
        ObjectProvider<DataSource> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(ds);
        return p;
    }

    @Nested
    class ConsentLookup {

        @Test
        void onlyPrivateAndSharedAnonAllowStoring() {
            TrainingConsentService svc = new TrainingConsentService(provider(consentDb(
                    "F_PRIV=private", "F_SHARE=shared_anon", "F_NONE=none", "F_ODD=everything")),
                    Clock.systemUTC());
            assertThat(svc.mayStoreTrainingData("F_PRIV")).isTrue();
            assertThat(svc.mayStoreTrainingData("F_SHARE")).isTrue();
            assertThat(svc.mayStoreTrainingData("F_NONE")).isFalse();
            assertThat(svc.mayStoreTrainingData("F_ODD")).isFalse();
            assertThat(svc.mayStoreTrainingData("F_NO_ROW")).isFalse();
            assertThat(svc.mayStoreTrainingData(null)).isFalse();
            assertThat(svc.mayStoreTrainingData(" ")).isFalse();
        }

        @Test
        void missingSmartbiDatasourceFailsClosed() {
            TrainingConsentService svc = new TrainingConsentService(provider(null), Clock.systemUTC());
            assertThat(svc.mayStoreTrainingData("F_PRIV")).isFalse();
        }

        @Test
        void unreadableConsentTableFailsClosedAndIsNotCached() {
            JdbcDataSource empty = new JdbcDataSource(); // no training_consent table
            empty.setURL("jdbc:h2:mem:noconsent_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            TrainingConsentService svc = new TrainingConsentService(provider(empty), Clock.systemUTC());
            assertThat(svc.mayStoreTrainingData("F_PRIV")).isFalse();
            // table appears (migration applied) -> next call reads it, the failure was not cached
            new JdbcTemplate(empty).execute(
                    "CREATE TABLE training_consent (factory_id VARCHAR(64) PRIMARY KEY, consent_scope VARCHAR(32))");
            new JdbcTemplate(empty).update("INSERT INTO training_consent VALUES ('F_PRIV', 'private')");
            assertThat(svc.mayStoreTrainingData("F_PRIV")).isTrue();
        }

        @Test
        void revokedConsentTakesEffectAfterTheCacheWindow() {
            DataSource ds = consentDb("F_PRIV=private");
            Clock t0 = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
            TrainingConsentService svc = new TrainingConsentService(provider(ds), t0);
            assertThat(svc.mayStoreTrainingData("F_PRIV")).isTrue();
            new JdbcTemplate(ds).update("UPDATE training_consent SET consent_scope = 'none'");
            ReflectionTestUtils.setField(svc, "clock",
                    Clock.fixed(Instant.parse("2026-10-24T00:01:01Z"), ZoneOffset.UTC));
            assertThat(svc.mayStoreTrainingData("F_PRIV")).isFalse();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class IntentTrainingSamples {
        @Mock TrainingSampleRepository sampleRepository;
        @Mock TrainingConsentService trainingConsentService;
        @InjectMocks ExpressionLearningServiceImpl service;

        @Test
        void unconsentedFactoryUtteranceIsNotStored() {
            when(trainingConsentService.mayStoreTrainingData("F006")).thenReturn(false);
            assertThat(service.recordSample("F006", "今天生产了多少", "PRODUCTION_QUERY",
                    com.cretas.aims.entity.learning.TrainingSample.MatchMethod.EXACT, 0.9, "s1")).isNull();
            verify(sampleRepository, never()).save(any());
        }

        @Test
        void consentedFactoryUtteranceIsStored() {
            when(trainingConsentService.mayStoreTrainingData("F_PRIV")).thenReturn(true);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            assertThat(service.recordSample("F_PRIV", "今天生产了多少", "PRODUCTION_QUERY",
                    com.cretas.aims.entity.learning.TrainingSample.MatchMethod.EXACT, 0.9, "s1")).isNotNull();
            verify(sampleRepository).save(any());
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class ActiveLearningSamples {
        @Mock ActiveLearningSampleRepository sampleRepository;
        @Mock TrainingConsentService trainingConsentService;
        @InjectMocks ActiveLearningServiceImpl service;

        @BeforeEach
        void threshold() {
            ReflectionTestUtils.setField(service, "confidenceThreshold", new BigDecimal("0.7"));
        }

        @Test
        void unconsentedFactoryLowConfidenceInputIsNotCollected() {
            when(trainingConsentService.mayStoreTrainingData("F006")).thenReturn(false);
            assertThat(service.collectSample("F006", 1L, "那个东西还剩多少", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", null, null, "rec-1")).isNull();
            verifyNoInteractions(sampleRepository);
        }

        @Test
        void consentedFactoryLowConfidenceInputIsCollected() {
            when(trainingConsentService.mayStoreTrainingData("F_PRIV")).thenReturn(true);
            when(sampleRepository.existsBySourceRecordId(anyString())).thenReturn(false);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            assertThat(service.collectSample("F_PRIV", 1L, "那个东西还剩多少", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", null, null, "rec-1")).isNotNull();
            verify(sampleRepository).save(any());
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class SchedulingTrainingData {
        @Mock TrainingDataRepository trainingDataRepository;
        @Mock TrainingConsentService trainingConsentService;
        @InjectMocks ProductionDataCollectorService service;

        @Test
        void unconsentedFactoryCompletedBatchIsNotRecorded() {
            ProductionBatch batch = new ProductionBatch();
            batch.setFactoryId("F006");
            batch.setId(42L);
            when(trainingConsentService.mayStoreTrainingData("F006")).thenReturn(false);
            service.onBatchCompleted(new BatchCompletedEvent(this, batch));
            assertThat(service.collectHistoricalData("F006", List.of(batch))).isZero();
            verifyNoInteractions(trainingDataRepository);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class SyntheticSamples {
        @Mock TrainingSampleRepository trainingSampleRepository;
        @Mock com.cretas.aims.ai.synthetic.IntentSkelBuilder intentSkelBuilder;
        @Mock TrainingConsentService trainingConsentService;
        @InjectMocks SyntheticDataService service;

        @Test
        void unconsentedFactoryRealSamplesAreNotUsedToSynthesize() {
            when(trainingConsentService.mayStoreTrainingData("F006")).thenReturn(false);
            SyntheticDataService.SyntheticGenerationResult r = service.generateForIntent("X", "F006", 10);
            assertThat(r.getSaved()).isZero();
            verifyNoInteractions(intentSkelBuilder, trainingSampleRepository);
        }
    }
}
