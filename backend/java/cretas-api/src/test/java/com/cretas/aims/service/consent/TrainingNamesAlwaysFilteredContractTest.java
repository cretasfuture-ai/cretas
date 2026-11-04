package com.cretas.aims.service.consent;

import com.cretas.aims.ai.synthetic.GRAPEFilter;
import com.cretas.aims.ai.synthetic.IntentScenGenerator;
import com.cretas.aims.ai.synthetic.IntentSkelBuilder;
import com.cretas.aims.ai.synthetic.IntentValidator;
import com.cretas.aims.ai.synthetic.SyntheticDataService;
import com.cretas.aims.config.SyntheticDataConfig;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.repository.learning.LearnedExpressionRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleSecrecyHoldRepository;
import com.cretas.aims.service.FactoryConfigService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.KeywordLearningService;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.impl.KeywordLearningServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Steve 2026-11-04: 人名、客户名、「工资 / 考勤词 + 数字」是<b>隐私</b>不是保密 —— 训练、学习、蒸馏样本里一律剔除,
 * 任何开关都不管; 保密产品名与「工艺词 + 数值」才归「训练数据过滤」(d) 管。
 *
 * <p>每条路径都走真入口 (收意图样本、收低置信样本、学说法、学关键词、学习建议、学习闸、挂起重判、合成样本), 用真过滤器,
 * 只把 d 开关、训练授权和查库换成可控的。授权两种都跑: 未授权工厂的文本是脱敏后才存的, 隐私必须在脱敏<b>之前</b>判。
 */
class TrainingNamesAlwaysFilteredContractTest {

    static final String F = "F006";
    static final String PERSON = "王五 出货情况";
    static final String CUSTOMER = "华联超市 出货情况";
    /** 名字里带数字: 脱敏后是「<数值>个核桃」, 只有判原文才认得出。 */
    static final String NUMERAL_CUSTOMER = "六个核桃 出货情况";
    static final String SECRET = "秘制牛肉干 出货情况";
    static final String PROCESS = "杀菌温度 121℃ 出货情况";
    static final String CLEAN = "今天 出货情况";
    static final String PAY = "本月工资 8000 元 出货情况";
    static final String ATTENDANCE = "考勤异常 3 人次 出货情况";
    /** Q2: 只有词没有数字, 照常存。 */
    static final String PAY_WORD_ONLY = "本月工资 出货情况";

    final AtomicBoolean trainingFilterOn = new AtomicBoolean(false);
    final AtomicBoolean directoryFails = new AtomicBoolean(false);
    final AtomicReference<TrainingConsentService.Status> consentStatus =
            new AtomicReference<>(TrainingConsentService.Status.AUTHORIZED);
    final List<String> storedTexts = new ArrayList<>();
    final AtomicInteger samplesSaved = new AtomicInteger();

    final ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
    final FactoryNameDirectoryRepository directory = mock(FactoryNameDirectoryRepository.class);
    final FactorySecrecyConfig secrecy = mock(FactorySecrecyConfig.class);
    final ProcessSecretSampleFilter filter =
            new ProcessSecretSampleFilter(access, mock(ProductTypeRepository.class), directory);

    final TrainingSampleRepository samples = mock(TrainingSampleRepository.class);
    final ActiveLearningSampleRepository alSamples = mock(ActiveLearningSampleRepository.class);
    final LearnedExpressionRepository expressionRepo = mock(LearnedExpressionRepository.class);
    final AIIntentConfigRepository intentConfigs = mock(AIIntentConfigRepository.class);
    final TrainingConsentService consent = mock(TrainingConsentService.class);
    final TrainingSampleHoldStore holdStore = mock(TrainingSampleHoldStore.class);
    final TrainingSampleSecrecyHoldRepository holds = mock(TrainingSampleSecrecyHoldRepository.class);

    final ExpressionLearningServiceImpl expressions = new ExpressionLearningServiceImpl(
            expressionRepo, samples, null, null, null, consent, filter);
    final ActiveLearningServiceImpl active = new ActiveLearningServiceImpl(
            alSamples, null, null, null, null, null, null, null, null, consent, filter, null, null, null, null);
    final IntentScenGenerator generator = mock(IntentScenGenerator.class);
    KeywordLearningServiceImpl keywords;
    TrainingSampleHoldRejudger rejudger;
    SyntheticDataService synthetic;

    @BeforeEach
    void wire() {
        filter.setFactorySecrecyConfig(secrecy);
        when(secrecy.isOn(F, SecrecyItem.TRAINING_FILTER)).thenAnswer(i -> trainingFilterOn.get());
        when(access.protectedProductTypeIds(F)).thenReturn(Set.of("P1"));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"P1", "PTF0060159", "秘制牛肉干", null, null});
        when(directory.findProtectedProductRows(eq(F), any())).thenReturn(rows);
        when(directory.findPolicyAliases(F)).thenReturn(List.of());
        when(directory.findPeopleNames(F)).thenAnswer(i -> {
            if (directoryFails.get()) {
                throw new DataAccessResourceFailureException("connection reset");
            }
            return List.of("王五");
        });
        when(directory.findCustomerNames(F)).thenReturn(List.of("华联超市", "六个核桃"));

        when(consent.status(anyString())).thenAnswer(i -> consentStatus.get());
        when(samples.save(any())).thenAnswer(i -> {
            samplesSaved.incrementAndGet();
            storedTexts.add(((TrainingSample) i.getArgument(0)).getUserInput());
            return i.getArgument(0);
        });
        when(samples.saveAll(any())).thenAnswer(i -> {
            List<TrainingSample> batch = i.getArgument(0);
            batch.forEach(s -> storedTexts.add(s.getUserInput()));
            return batch;
        });
        when(alSamples.save(any())).thenAnswer(i -> {
            storedTexts.add(((com.cretas.aims.entity.learning.ActiveLearningSample) i.getArgument(0)).getUserInput());
            return i.getArgument(0);
        });
        when(expressionRepo.findOneByHashAndFactory(anyString(), any())).thenReturn(Optional.empty());
        when(expressionRepo.save(any(LearnedExpression.class))).thenAnswer(i -> i.getArgument(0));
        when(intentConfigs.findByFactoryIdAndIntentCode(F, "PRODUCTION_QUERY"))
                .thenAnswer(i -> Optional.of(intentRow()));

        ReflectionTestUtils.setField(expressions, "holdStore", holdStore);
        ReflectionTestUtils.setField(active, "holdStore", holdStore);
        ReflectionTestUtils.setField(active, "confidenceThreshold", new BigDecimal("0.7"));
        FactoryConfigService factoryConfig = mock(FactoryConfigService.class);
        when(factoryConfig.getMaxKeywordsPerIntent(anyString())).thenReturn(50);
        when(factoryConfig.getLlmNewKeywordWeight(anyString())).thenReturn(BigDecimal.ONE);
        keywords = new KeywordLearningServiceImpl(intentConfigs, mock(KeywordEffectivenessService.class), factoryConfig,
                new ObjectMapper(), consent, filter);
        rejudger = new TrainingSampleHoldRejudger(holds, filter, expressions, active, Duration.ofDays(7),
                Clock.fixed(Instant.parse("2026-11-04T08:00:00Z"), ZoneId.of("UTC")));

        IntentSkelBuilder skeletons = mock(IntentSkelBuilder.class);
        when(skeletons.buildFromHistory(anyString(), anyString())).thenReturn(mock(IntentSkelBuilder.IntentSkel.class));
        IntentValidator.ValidationResult valid = mock(IntentValidator.ValidationResult.class);
        when(valid.isValid()).thenReturn(true);
        IntentValidator validator = mock(IntentValidator.class);
        when(validator.validate(any())).thenReturn(valid);
        GRAPEFilter grape = mock(GRAPEFilter.class);
        when(grape.filter(any())).thenAnswer(i -> i.getArgument(0));
        SyntheticDataConfig syntheticConfig = mock(SyntheticDataConfig.class);
        when(syntheticConfig.getMaxSyntheticRatio()).thenReturn(1.0);
        when(syntheticConfig.getCandidateMultiplier()).thenReturn(1);
        synthetic = new SyntheticDataService(skeletons, generator, validator, grape, samples, syntheticConfig,
                consent, filter);
    }

    static AIIntentConfig intentRow() {
        AIIntentConfig c = new AIIntentConfig();
        c.setIntentCode("PRODUCTION_QUERY");
        c.setFactoryId(F);
        c.setKeywords("[]");
        c.setIsActive(true);
        return c;
    }

    /** 路径名 → 这段话走这条路径会不会被存下 / 学进去。 */
    Map<String, Predicate<String>> paths() {
        Map<String, Predicate<String>> p = new LinkedHashMap<>();
        p.put("recordSample", t -> expressions.recordSample(F, t, "PRODUCTION_QUERY",
                TrainingSample.MatchMethod.EXACT, 0.9, "s1") != null);
        p.put("collectSample", t -> active.collectSample(F, 1L, t, null, "PRODUCTION_QUERY",
                new BigDecimal("0.3"), "SEMANTIC", null, null, "rec-1") != null);
        p.put("collectSample.matchedKeywords", t -> active.collectSample(F, 1L, "那批还剩多少", null, "PRODUCTION_QUERY",
                new BigDecimal("0.3"), "SEMANTIC", null, t, "rec-2") != null);
        p.put("learnExpressions", t -> expressions.learnExpressions(F, "PRODUCTION_QUERY", List.of(t), 1.0,
                LearnedExpression.SourceType.LLM_FALLBACK) == 1);
        p.put("keywordLearning", t -> keywords.learnFromMatchedIntent(t, intentRow(), F) > 0);
        p.put("factoryLearnable", t -> LearningContentRules.factoryLearnable(consent, filter, F, t));
        p.put("rejudger", this::rejudgedAndStored);
        p.put("synthetic", this::synthesizedAndStored);
        return p;
    }

    /** 合成样本走真的 generateForIntent, 只把生成器换成吐这段话。 */
    boolean synthesizedAndStored(String text) {
        when(generator.generate(any(), anyInt())).thenReturn(List.of(IntentScenGenerator.SyntheticSample.builder()
                .userInput(text).intentCode("PRODUCTION_QUERY").generatorConfidence(0.9).skeletonId("sk").build()));
        return synthetic.generateForIntent("PRODUCTION_QUERY", F, 1).getSaved() == 1;
    }

    /** 挂起行按当时的 d 重判: 放行时走真的 recordSample, 判「真的写进了样本表」。 */
    boolean rejudgedAndStored(String text) {
        try {
            TrainingSampleSecrecyHold h = TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.INTENT, F,
                    TrainingSampleHoldStore.JSON.writeValueAsString(new TrainingSampleHoldStore.IntentSample(
                            text, "PRODUCTION_QUERY", "EXACT", 0.9, "s1")), LocalDateTime.of(2026, 11, 4, 7, 0));
            h.setId(1L);
            when(holds.findNextPending(any())).thenReturn(List.of(h));
            int before = samplesSaved.get();
            rejudger.rejudgeOnce();
            return samplesSaved.get() > before;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void withTheTrainingFilterOffPersonAndCustomerNamesAreStillNeverStoredOrLearned() {
        trainingFilterOn.set(false);
        paths().forEach((path, accepted) -> {
            assertThat(accepted.test(PERSON)).as(path + ": 人名").isFalse();
            assertThat(accepted.test(CUSTOMER)).as(path + ": 客户名").isFalse();
            assertThat(accepted.test(CLEAN)).as(path + ": 对照, 无关的话照常").isTrue();
        });
    }

    /** 逐条路径收集, 红的时候一次列全哪几条路径漏了。 */
    List<String> accepting(String text) {
        List<String> leaked = new ArrayList<>();
        paths().forEach((path, accepted) -> {
            if (accepted.test(text)) {
                leaked.add(path);
            }
        });
        return leaked;
    }

    @Test
    void withTheTrainingFilterOffAPayOrAttendanceWordWithANumberIsNeverStoredOrLearned() {
        trainingFilterOn.set(false);
        assertThat(accepting(PAY)).as("工资 + 数字").isEmpty();
        assertThat(accepting(ATTENDANCE)).as("考勤 + 数字").isEmpty();
        assertThat(paths().keySet()).hasSize(8);
        assertThat(accepting(CLEAN)).as("对照: 无关的话每条路径都收").hasSize(8);
    }

    @Test
    void withTheTrainingFilterOnAPayOrAttendanceWordWithANumberIsNeverStoredOrLearnedEither() {
        trainingFilterOn.set(true);
        assertThat(accepting(PAY)).as("工资 + 数字").isEmpty();
        assertThat(accepting(ATTENDANCE)).as("考勤 + 数字").isEmpty();
    }

    @Test
    void aPayWordWithoutANumberStillPassesWhicheverWayTheTrainingFilterIsSet() {
        for (boolean on : new boolean[]{false, true}) {
            trainingFilterOn.set(on);
            assertThat(accepting(PAY_WORD_ONLY)).as("d=" + on).hasSize(8);
        }
    }

    /** 未授权工厂存的是脱敏文本, 而脱敏后工资数字和带数字的名字都看不出来了: 每条路径都必须判原文。 */
    @Test
    void withoutAuthorizationPrivacyIsJudgedOnTheRawTextBeforeAnythingIsMasked() {
        consentStatus.set(TrainingConsentService.Status.DEFAULT);
        for (boolean on : new boolean[]{false, true}) {
            trainingFilterOn.set(on);
            assertThat(accepting(PERSON)).as("人名, d=" + on).isEmpty();
            assertThat(accepting(CUSTOMER)).as("客户名, d=" + on).isEmpty();
            assertThat(accepting(NUMERAL_CUSTOMER)).as("带数字的客户名, d=" + on).isEmpty();
            assertThat(accepting(PAY)).as("工资 + 数字, d=" + on).isEmpty();
            assertThat(accepting(ATTENDANCE)).as("考勤 + 数字, d=" + on).isEmpty();
            assertThat(accepting(CLEAN)).as("对照: 无关的话每条路径都收, d=" + on).hasSize(8);
        }
    }

    @Test
    void aNameWithANumeralIsAlsoBlockedForAnAuthorizedFactory() {
        assertThat(accepting(NUMERAL_CUSTOMER)).isEmpty();
    }

    /** 写样本表的四条路径: 未授权脱敏后存, 已授权原样存 (阳性对照, 证明脱敏是授权决定的)。 */
    @Test
    void everySamplePathStoresMaskedNumbersOnlyWithoutAuthorization() {
        String text = "入库 5 箱 出货情况";
        Map<String, Predicate<String>> p = paths();
        for (String path : List.of("recordSample", "collectSample", "rejudger", "synthetic")) {
            consentStatus.set(TrainingConsentService.Status.DEFAULT);
            storedTexts.clear();
            assertThat(p.get(path).test(text)).as(path).isTrue();
            assertThat(storedTexts).as(path + ": 未授权").containsExactly("入库 <数值> 箱 出货情况");

            consentStatus.set(TrainingConsentService.Status.AUTHORIZED);
            storedTexts.clear();
            assertThat(p.get(path).test(text)).as(path).isTrue();
            assertThat(storedTexts).as(path + ": 已授权").containsExactly(text);
        }
    }

    @Test
    void aFactoryThatWithdrewOrCannotBeReadStoresAndLearnsNothing() {
        for (TrainingConsentService.Status status : List.of(TrainingConsentService.Status.WITHDRAWN,
                TrainingConsentService.Status.UNKNOWN)) {
            consentStatus.set(status);
            assertThat(accepting(CLEAN)).as(status.name()).isEmpty();
        }
    }

    /** 合成样本: 挡掉的不占名额, 目标数照样凑够; 计数如实报。 */
    @Test
    void aBlockedSyntheticSampleDoesNotUseUpTheTargetAndIsCounted() {
        when(generator.generate(any(), anyInt())).thenReturn(List.of(
                IntentScenGenerator.SyntheticSample.builder().userInput(PERSON).intentCode("PRODUCTION_QUERY")
                        .generatorConfidence(0.9).skeletonId("sk").build(),
                IntentScenGenerator.SyntheticSample.builder().userInput(CLEAN).intentCode("PRODUCTION_QUERY")
                        .generatorConfidence(0.9).skeletonId("sk").build()));
        SyntheticDataService.SyntheticGenerationResult result = synthetic.generateForIntent("PRODUCTION_QUERY", F, 1);
        assertThat(storedTexts).containsExactly(CLEAN);
        assertThat(result.getSaved()).isEqualTo(1);
        assertThat(result.getPrivacyDropped()).isEqualTo(1);
    }

    /** 名单查不出来时合成样本不写: 合成样本没有挂起表, HELD 和 UNVERIFIED 都是不存。 */
    @Test
    void aSyntheticSampleIsNotStoredWhenTheDirectoryLookupFails() {
        assertThat(synthesizedAndStored(CLEAN)).as("阳性对照").isTrue();
        storedTexts.clear();
        filter.invalidateNameCache();
        directoryFails.set(true);
        assertThat(synthesizedAndStored(CLEAN)).as("HELD").isFalse();
        assertThat(storedTexts).isEmpty();
    }

    /** 名单查不出来时会挂起待重判, 工资数字不许跟着进挂起表: 内容规则先于名单判。 */
    @Test
    void aPayValueIsNotHeldWhenTheDirectoryLookupFails() {
        trainingFilterOn.set(false);
        assertThat(filter.checkTrainingSample("warm", F, CLEAN)).isEqualTo(ProcessSecretSampleFilter.SampleVerdict.CLEAR);
        filter.invalidateNameCache();
        directoryFails.set(true);
        assertThat(expressions.recordSample(F, PAY, "PRODUCTION_QUERY", TrainingSample.MatchMethod.EXACT, 0.9, "s1"))
                .isNull();
        assertThat(active.collectSample(F, 1L, PAY, null, "PRODUCTION_QUERY", new BigDecimal("0.3"), "SEMANTIC",
                null, null, "rec-1")).isNull();
        verify(holdStore, never()).holdIntent(anyString(), any());
        verify(holdStore, never()).holdActiveLearning(anyString(), any());
    }

    /** 候选意图里的数字是分数不是工资: 工资规则不看 topCandidates, 否则问考勤的低置信样本全收不到。 */
    @Test
    void aCandidateScoreNextToAnAttendanceIntentIsNotAPayValue() {
        trainingFilterOn.set(false);
        assertThat(active.collectSample(F, 1L, "看下考勤", null, "ATTENDANCE_QUERY", new BigDecimal("0.3"), "SEMANTIC",
                "考勤 0.81", null, "rec-3")).isNotNull();
    }

    @Test
    void withTheTrainingFilterOffProtectedNamesAndProcessValuesPass() {
        trainingFilterOn.set(false);
        paths().forEach((path, accepted) -> {
            assertThat(accepted.test(SECRET)).as(path + ": 保密产品名").isTrue();
            assertThat(accepted.test(PROCESS)).as(path + ": 工艺词 + 数值").isTrue();
        });
    }

    @Test
    void withTheTrainingFilterOnEverythingIsBlocked() {
        trainingFilterOn.set(true);
        paths().forEach((path, accepted) -> {
            assertThat(accepted.test(PERSON)).as(path + ": 人名").isFalse();
            assertThat(accepted.test(CUSTOMER)).as(path + ": 客户名").isFalse();
            assertThat(accepted.test(SECRET)).as(path + ": 保密产品名").isFalse();
            if (!"collectSample.matchedKeywords".equals(path)) {     // 内容规则只看原话, 名单才看匹配到的关键词
                assertThat(accepted.test(PROCESS)).as(path + ": 工艺词 + 数值").isFalse();
            }
            assertThat(accepted.test(CLEAN)).as(path + ": 对照").isTrue();
        });
    }

    @Test
    void withTheTrainingFilterOffAFailingDirectoryLookupWithNoLastGoodListsStoresNothing() {
        trainingFilterOn.set(false);
        directoryFails.set(true);
        assertThat(filter.checkTrainingSample("intent", F, CLEAN))
                .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.UNVERIFIED);
        assertThat(expressions.recordSample(F, CLEAN, "PRODUCTION_QUERY", TrainingSample.MatchMethod.EXACT, 0.9, "s1"))
                .isNull();
        assertThat(active.collectSample(F, 1L, CLEAN, null, "PRODUCTION_QUERY", new BigDecimal("0.3"), "SEMANTIC",
                null, null, "rec-1")).isNull();
        assertThat(LearningContentRules.factoryLearnable(consent, filter, F, CLEAN)).isFalse();
        verify(samples, never()).save(any());
        verify(alSamples, never()).save(any());
    }

    @Test
    void withTheTrainingFilterOffAFailingDirectoryLookupFallsBackToTheLastGoodListsAndHolds() {
        trainingFilterOn.set(false);
        assertThat(filter.checkTrainingSample("warm", F, CLEAN)).isEqualTo(ProcessSecretSampleFilter.SampleVerdict.CLEAR);
        filter.invalidateNameCache();                  // 名单缓存过期, 下一次必须真查库
        directoryFails.set(true);

        // 上次成功的名单里有 → 不存、也不挂起
        assertThat(filter.checkTrainingSample("intent", F, PERSON)).isEqualTo(ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
        assertThat(expressions.recordSample(F, PERSON, "PRODUCTION_QUERY", TrainingSample.MatchMethod.EXACT, 0.9, "s1"))
                .isNull();
        verify(holdStore, never()).holdIntent(anyString(), any());
        // 没提到 → 挂起待重判, 不直接存; 学习入口没有「挂起」可放, 不学
        assertThat(expressions.recordSample(F, CLEAN, "PRODUCTION_QUERY", TrainingSample.MatchMethod.EXACT, 0.9, "s1"))
                .isNull();
        verify(holdStore).holdIntent(eq(F), any());
        assertThat(LearningContentRules.factoryLearnable(consent, filter, F, CLEAN)).isFalse();
        verify(samples, never()).save(any());
    }

    int addManually(String keyword) {
        return keywords.addKeywordsToIntent(F, "PRODUCTION_QUERY", List.of(keyword),
                KeywordLearningService.LearnSource.MANUAL_ADD);
    }

    /** MANUAL_ADD 现在没有生产调用方, 先把门装好: 以后谁接上来都带着隐私检查。 */
    @Test
    void aManualKeywordCannotBeAPersonACustomerOrAPayValueWhicheverWayTheTrainingFilterIsSet() {
        for (boolean on : new boolean[]{false, true}) {
            trainingFilterOn.set(on);
            assertThat(addManually("王五")).as("人名, d=" + on).isZero();
            assertThat(addManually("华联超市")).as("客户名, d=" + on).isZero();
            assertThat(addManually("本月工资 8000 元")).as("工资 + 数字, d=" + on).isZero();
            assertThat(addManually("出货情况")).as("对照, d=" + on).isEqualTo(1);
        }
    }

    /** 原有口径不变: 保密词跟 d, 不查工艺词 + 数值, 不按训练授权拦。 */
    @Test
    void aManualKeywordKeepsItsOtherRules() {
        trainingFilterOn.set(true);
        assertThat(addManually("秘制牛肉干")).as("保密词, d 开").isZero();
        assertThat(addManually("杀菌温度 121℃")).as("工艺词 + 数值").isEqualTo(1);
        trainingFilterOn.set(false);
        assertThat(addManually("秘制牛肉干")).as("保密词, d 关").isEqualTo(1);
        consentStatus.set(TrainingConsentService.Status.WITHDRAWN);
        assertThat(addManually("出货情况")).as("不看训练授权").isEqualTo(1);
    }

    @Test
    void aManualKeywordIsNotAddedWhenTheDirectoryLookupFails() {
        directoryFails.set(true);
        assertThat(addManually("出货情况")).isZero();
    }
}
