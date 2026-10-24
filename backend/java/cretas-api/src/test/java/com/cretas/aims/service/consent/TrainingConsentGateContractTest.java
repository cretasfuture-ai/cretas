package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.ActiveLearningSample;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Steve 2026-10-24 训练规则 (Java 侧: 意图对应样本)。配方 / 工艺参数类原话任何工厂都不存;
 * 其余默认存, 未授权工厂脱敏 (数字、手机号、证件号、卡号 → 占位符), 已授权原样存。
 * 授权读自 SmartBI 库 training_consent, 读不到按未授权。规则与 Python 共用
 * backend/python/smartbi/services/training_sample_rule_cases.json。
 */
class TrainingConsentGateContractTest {

    static final Path SHARED_CASES = Path.of("..", "..", "python", "smartbi", "services",
            "training_sample_rule_cases.json");

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
    class SharedCasesWithPython {

        @Test
        void utteranceRuleMatchesTheSharedCaseFile() throws Exception {
            assertThat(SHARED_CASES).as("shared case file must exist (no silent skip)").exists();
            JsonNode cases = new ObjectMapper()
                    .readTree(Files.readString(SHARED_CASES, StandardCharsets.UTF_8)).get("utterance_cases");
            assertThat(cases.size()).isGreaterThanOrEqualTo(10);
            List<String> mismatches = new ArrayList<>();
            for (JsonNode c : cases) {
                String text = c.get("text").asText();
                boolean authorized = c.get("authorized").asBoolean();
                String expected = c.get("stored").isNull() ? null : c.get("stored").asText();
                String actual = TrainingSampleRules.textToStore(text, authorized);
                if (!java.util.Objects.equals(expected, actual)) {
                    mismatches.add(text + " (authorized=" + authorized + ") -> " + actual);
                }
            }
            assertThat(mismatches).isEmpty();
        }
    }

    @Nested
    class AuthorizationLookup {

        @Test
        void onlyPrivateAndSharedAnonAreAuthorized() {
            TrainingConsentService svc = new TrainingConsentService(provider(consentDb(
                    "F_PRIV=private", "F_SHARE=shared_anon", "F_NONE=none", "F_ODD=everything")),
                    Clock.systemUTC());
            assertThat(svc.isAuthorized("F_PRIV")).isTrue();
            assertThat(svc.isAuthorized("F_SHARE")).isTrue();
            assertThat(svc.isAuthorized("F_NONE")).isFalse();
            assertThat(svc.isAuthorized("F_ODD")).isFalse();
            assertThat(svc.isAuthorized("F_NO_ROW")).isFalse();
            assertThat(svc.isAuthorized(null)).isFalse();
            assertThat(svc.isAuthorized(" ")).isFalse();
        }

        @Test
        void missingSmartbiDatasourceMeansNotAuthorized() {
            TrainingConsentService svc = new TrainingConsentService(provider(null), Clock.systemUTC());
            assertThat(svc.isAuthorized("F_PRIV")).isFalse();
        }

        @Test
        void unreadableConsentTableMeansNotAuthorizedAndIsNotCached() {
            JdbcDataSource empty = new JdbcDataSource(); // no training_consent table
            empty.setURL("jdbc:h2:mem:noconsent_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            TrainingConsentService svc = new TrainingConsentService(provider(empty), Clock.systemUTC());
            assertThat(svc.isAuthorized("F_PRIV")).isFalse();
            new JdbcTemplate(empty).execute(
                    "CREATE TABLE training_consent (factory_id VARCHAR(64) PRIMARY KEY, consent_scope VARCHAR(32))");
            new JdbcTemplate(empty).update("INSERT INTO training_consent VALUES ('F_PRIV', 'private')");
            assertThat(svc.isAuthorized("F_PRIV")).isTrue();
        }

        @Test
        void revokedAuthorizationTakesEffectAfterTheCacheWindow() {
            DataSource ds = consentDb("F_PRIV=private");
            Clock t0 = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
            TrainingConsentService svc = new TrainingConsentService(provider(ds), t0);
            assertThat(svc.isAuthorized("F_PRIV")).isTrue();
            new JdbcTemplate(ds).update("UPDATE training_consent SET consent_scope = 'none'");
            ReflectionTestUtils.setField(svc, "clock",
                    Clock.fixed(Instant.parse("2026-10-24T00:01:01Z"), ZoneOffset.UTC));
            assertThat(svc.isAuthorized("F_PRIV")).isFalse();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class IntentTrainingSamples {
        @Mock TrainingSampleRepository sampleRepository;
        @Mock TrainingConsentService trainingConsentService;
        @InjectMocks ExpressionLearningServiceImpl service;

        @Test
        void recipeUtteranceIsNotStoredEvenWhenAuthorized() {
            when(trainingConsentService.isAuthorized("F_PRIV")).thenReturn(true);
            assertThat(service.recordSample("F_PRIV", "这个产品配方里盐放多少", "BOM_RECIPE_QUERY",
                    TrainingSample.MatchMethod.EXACT, 0.9, "s1")).isNull();
            verify(sampleRepository, never()).save(any());
        }

        @Test
        void unauthorizedFactoryUtteranceIsStoredDesensitized() {
            when(trainingConsentService.isAuthorized("F006")).thenReturn(false);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            service.recordSample("F006", "今天入库 350 箱，联系 13812345678", "MATERIAL_INBOUND",
                    TrainingSample.MatchMethod.EXACT, 0.9, "s1");
            ArgumentCaptor<TrainingSample> saved = ArgumentCaptor.forClass(TrainingSample.class);
            verify(sampleRepository).save(saved.capture());
            assertThat(saved.getValue().getUserInput()).isEqualTo("今天入库 <数值> 箱，联系 <电话>");
        }

        @Test
        void authorizedFactoryUtteranceIsStoredAsIs() {
            when(trainingConsentService.isAuthorized("F_PRIV")).thenReturn(true);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            service.recordSample("F_PRIV", "今天入库 350 箱", "MATERIAL_INBOUND",
                    TrainingSample.MatchMethod.EXACT, 0.9, "s1");
            ArgumentCaptor<TrainingSample> saved = ArgumentCaptor.forClass(TrainingSample.class);
            verify(sampleRepository).save(saved.capture());
            assertThat(saved.getValue().getUserInput()).isEqualTo("今天入库 350 箱");
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
        void processParameterInputIsNotCollected() {
            assertThat(service.collectSample("F006", 1L, "杀菌温度调到多少合适", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", null, null, "rec-1")).isNull();
            verifyNoInteractions(sampleRepository);
        }

        @Test
        void unauthorizedFactoryLowConfidenceInputIsCollectedDesensitized() {
            when(trainingConsentService.isAuthorized("F006")).thenReturn(false);
            when(sampleRepository.existsBySourceRecordId(anyString())).thenReturn(false);
            when(sampleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            ActiveLearningSample s = service.collectSample("F006", 1L, "那批 200 箱还剩多少", "那批 200 箱 剩余",
                    "X", new BigDecimal("0.3"), "SEMANTIC", null, "200箱", "rec-1");
            assertThat(s.getUserInput()).isEqualTo("那批 <数值> 箱还剩多少");
            assertThat(s.getNormalizedInput()).isEqualTo("那批 <数值> 箱 剩余");
            assertThat(s.getMatchedKeywords()).isEqualTo("<数值>箱");
        }
    }
}
