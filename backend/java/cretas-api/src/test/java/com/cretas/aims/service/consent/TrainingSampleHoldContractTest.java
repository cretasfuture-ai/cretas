package com.cretas.aims.service.consent;

import com.cretas.aims.entity.learning.TrainingSample;
import com.cretas.aims.entity.learning.TrainingSampleSecrecyHold;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.learning.ActiveLearningSampleRepository;
import com.cretas.aims.repository.learning.FactoryNameDirectoryRepository;
import com.cretas.aims.repository.learning.TrainingSampleRepository;
import com.cretas.aims.repository.learning.TrainingSampleSecrecyHoldRepository;
import com.cretas.aims.service.ActiveLearningService;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.impl.ActiveLearningServiceImpl;
import com.cretas.aims.service.impl.ExpressionLearningServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 过滤规则待办第 2 / 9 条 + Steve 2026-11-03 Q5 + 安全复审 N3/N4: 本厂名单查不出来时按<b>上次成功的名单</b>判,
 * 判过的样本以<b>原文</b>挂起 (training_sample_secrecy_holds, Flyway V20261104_11), {@link TrainingSampleHoldRejudger}
 * 按当前名单重判: 过了先删挂起行再走原收样本入口; 仍提到名单 → REJECTED 保留给人工复核 (不删); 读不出来的到期删。
 */
class TrainingSampleHoldContractTest {

    static final String FACTORY = "F006";

    /** 真过滤器: 名单查询可以切换成失败; 保密产品「秘制牛肉干」(编码 PTF0060159), 本厂人员「王五」。 */
    static class Lists {
        final AtomicBoolean fail = new AtomicBoolean(false);
        final ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        final FactoryNameDirectoryRepository directory = mock(FactoryNameDirectoryRepository.class);
        final ProcessSecretSampleFilter filter =
                new ProcessSecretSampleFilter(access, mock(ProductTypeRepository.class), directory);

        Lists() {
            when(access.protectedProductTypeIds(FACTORY)).thenAnswer(i -> {
                if (fail.get()) {
                    throw new DataAccessResourceFailureException("connection reset");
                }
                return Set.of("P1");
            });
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"P1", "PTF0060159", "秘制牛肉干", null, null});
            rows.add(new Object[]{"P2", "PTSHHPSKGYLYXGS0124", "398礼盒", "原切", null});
            when(directory.findProtectedProductRows(eq(FACTORY), any())).thenReturn(rows);
            when(directory.findPolicyAliases(FACTORY)).thenReturn(List.of());
            when(directory.findPeopleNames(FACTORY)).thenReturn(List.of("王五"));
            when(directory.findCustomerNames(FACTORY)).thenReturn(List.of());
        }
    }

    @Nested
    class Verdicts {
        final Lists lists = new Lists();

        @Test
        void lastGoodListsAreUsedWhenTheLookupFails() {
            assertThat(lists.filter.checkTrainingSample("intent", FACTORY, "今天产量"))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.CLEAR);       // loads the lists once
            lists.fail.set(true);
            assertThat(lists.filter.checkTrainingSample("intent", FACTORY, "今天出货"))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.HELD);
            assertThat(lists.filter.checkTrainingSample("intent", FACTORY, "秘制牛肉干今天产量"))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.PROTECTED);   // the stale list still blocks
            assertThat(lists.filter.checkTrainingSample("intent", FACTORY, "操作员王五报工"))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.PROTECTED);
        }

        @Test
        void noLastGoodListsMeansUnverified() {
            lists.fail.set(true);
            assertThat(lists.filter.checkTrainingSample("intent", FACTORY, "今天出货"))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.UNVERIFIED);
        }
    }

    @Nested
    class Capture {
        final Lists lists = new Lists();
        final TrainingSampleRepository samples = mock(TrainingSampleRepository.class);
        final ActiveLearningSampleRepository alSamples = mock(ActiveLearningSampleRepository.class);
        final TrainingConsentService consent = mock(TrainingConsentService.class);
        final TrainingSampleHoldStore holdStore = mock(TrainingSampleHoldStore.class);
        final ExpressionLearningServiceImpl intents = new ExpressionLearningServiceImpl(
                null, samples, null, null, null, consent, lists.filter);
        final ActiveLearningServiceImpl active = new ActiveLearningServiceImpl(
                alSamples, null, null, null, null, null, null, null, null,
                consent, lists.filter, null, null, null, null);

        @BeforeEach
        void wire() {
            ReflectionTestUtils.setField(intents, "holdStore", holdStore);
            ReflectionTestUtils.setField(active, "holdStore", holdStore);
            ReflectionTestUtils.setField(active, "confidenceThreshold", new BigDecimal("0.7"));
            when(consent.status(FACTORY)).thenReturn(TrainingConsentService.Status.DEFAULT);
            lists.filter.checkTrainingSample("warm", FACTORY, "x");   // lists loaded once
            lists.fail.set(true);
        }

        @Test
        void heldIntentSampleKeepsTheRawTextForTheRejudgement() {
            // review N3: the hold keeps the RAW text; masking happens when the sample is released
            assertThat(intents.recordSample(FACTORY, " 今天入库 350 箱 PTF00601 ", "MATERIAL_INBOUND",
                    TrainingSample.MatchMethod.EXACT, 0.9, "s1")).isNull();
            ArgumentCaptor<TrainingSampleHoldStore.IntentSample> held =
                    ArgumentCaptor.forClass(TrainingSampleHoldStore.IntentSample.class);
            verify(holdStore).holdIntent(eq(FACTORY), held.capture());
            assertThat(held.getValue().userInput()).isEqualTo("今天入库 350 箱 PTF00601");
            assertThat(held.getValue().matchMethod()).isEqualTo("EXACT");
            verify(samples, never()).save(any());
        }

        @Test
        void heldActiveLearningSampleKeepsTheRawText() {
            assertThat(active.collectSample(FACTORY, 1L, "那批 200 箱还剩多少", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", "[\"X\"]", "200箱", "rec-1")).isNull();
            ArgumentCaptor<TrainingSampleHoldStore.ActiveLearningSampleData> held =
                    ArgumentCaptor.forClass(TrainingSampleHoldStore.ActiveLearningSampleData.class);
            verify(holdStore).holdActiveLearning(eq(FACTORY), held.capture());
            assertThat(held.getValue().userInput()).isEqualTo("那批 200 箱还剩多少");
            assertThat(held.getValue().matchedKeywords()).isEqualTo("200箱");
            verifyNoInteractions(alSamples);
        }

        @Test
        void aSampleTheStaleListBlocksIsNotHeld() {
            assertThat(intents.recordSample(FACTORY, "秘制牛肉干今天入库多少", "MATERIAL_INBOUND",
                    TrainingSample.MatchMethod.EXACT, 0.9, "s1")).isNull();
            verifyNoInteractions(holdStore);
            verify(samples, never()).save(any());
        }

        @Test
        void matchedKeywordsAndTopCandidatesAreCheckedToo() {
            lists.fail.set(false);
            assertThat(active.collectSample(FACTORY, 1L, "那批还剩多少", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", null, "秘制牛肉干", "rec-2")).isNull();
            assertThat(active.collectSample(FACTORY, 1L, "那批还剩多少", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", "[{\"intent\":\"PTF0060159\"}]", null, "rec-3")).isNull();
            verifyNoInteractions(alSamples);
            when(alSamples.save(any())).thenAnswer(i -> i.getArgument(0));
            assertThat(active.collectSample(FACTORY, 1L, "那批还剩多少", null, "X",
                    new BigDecimal("0.3"), "SEMANTIC", null, "那批", "rec-4")).isNotNull();   // control
        }
    }

    @Nested
    class Rejudger {
        final Lists lists = new Lists();
        final TrainingSampleSecrecyHoldRepository holds = mock(TrainingSampleSecrecyHoldRepository.class);
        final ExpressionLearningService intents = mock(ExpressionLearningService.class);
        final ActiveLearningService active = mock(ActiveLearningService.class);
        final Instant now = Instant.parse("2026-11-03T08:00:00Z");
        final TrainingSampleHoldRejudger rejudger = new TrainingSampleHoldRejudger(holds, lists.filter, intents,
                active, Duration.ofDays(7), Clock.fixed(now, ZoneId.of("UTC")));
        final LocalDateTime recent = LocalDateTime.of(2026, 11, 3, 7, 0);

        TrainingSampleSecrecyHold intentHold(String text, LocalDateTime createdAt) throws Exception {
            TrainingSampleSecrecyHold h = TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.INTENT, FACTORY,
                    TrainingSampleHoldStore.JSON.writeValueAsString(new TrainingSampleHoldStore.IntentSample(
                            text, "PRODUCTION_QUERY", "EXACT", 0.9, "s1")), createdAt);
            h.setId((long) text.hashCode());
            return h;
        }

        TrainingSampleSecrecyHold activeHold(String keywords) throws Exception {
            TrainingSampleSecrecyHold h = TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.ACTIVE_LEARNING,
                    FACTORY, TrainingSampleHoldStore.JSON.writeValueAsString(new TrainingSampleHoldStore.ActiveLearningSampleData(
                            1L, "那批还剩多少", null, "X", new BigDecimal("0.3"), "SEMANTIC", null, keywords, "rec-9")),
                    recent);
            h.setId(99L);
            return h;
        }

        @Test
        void cleanSamplesAreReleasedThroughTheOriginalEntryAndNamedOnesAreFlaggedAndKept() throws Exception {
            TrainingSampleSecrecyHold clean = intentHold("今天入库 350 箱", recent);
            TrainingSampleSecrecyHold named = intentHold("操作员王五报工", recent);
            TrainingSampleSecrecyHold alNamed = activeHold("秘制牛肉干");
            when(holds.findNextPending(any())).thenReturn(List.of(clean, named, alNamed));

            TrainingSampleHoldRejudger.Outcome o = rejudger.rejudgeOnce();

            assertThat(o).isEqualTo(new TrainingSampleHoldRejudger.Outcome(1, 2, 0, 0));
            // released with the RAW text: recordSample masks it as normal capture does
            verify(intents).recordSample(FACTORY, "今天入库 350 箱", "PRODUCTION_QUERY",
                    TrainingSample.MatchMethod.EXACT, 0.9, "s1");
            verify(holds).delete(clean);
            // Q5: flagged and kept for manual review, never deleted
            verify(holds, never()).delete(named);
            verify(holds, never()).delete(alNamed);
            assertThat(named.getReviewStatus()).isEqualTo(TrainingSampleSecrecyHold.ReviewStatus.REJECTED);
            assertThat(named.getRejectedAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0));
            assertThat(alNamed.getReviewStatus()).isEqualTo(TrainingSampleSecrecyHold.ReviewStatus.REJECTED);
            // second review, item 1: the raw text is dropped on rejection; reason and time stay
            assertThat(named.getPayload()).isEqualTo(TrainingSampleSecrecyHold.DROPPED_PAYLOAD);
            assertThat(alNamed.getPayload()).isEqualTo(TrainingSampleSecrecyHold.DROPPED_PAYLOAD);
            assertThat(named.getRejectReason()).isEqualTo("names_factory_list");
            verify(holds).save(named);
            verify(holds).save(alNamed);
            verifyNoInteractions(active);
        }

        @Test
        void rawTextIsWhatIsRejudged() throws Exception {
            // review N3: the secret name 「398礼盒」 is visible in the raw text; its masked form 「<数值>礼盒」 is not
            String raw = "398礼盒今天发了多少";
            String masked = TrainingSampleRules.maskNumbers(raw);
            assertThat(masked).isEqualTo("<数值>礼盒今天发了多少");
            assertThat(lists.filter.checkTrainingSample("probe", FACTORY, masked))
                    .isEqualTo(ProcessSecretSampleFilter.SampleVerdict.CLEAR);          // masked text would be released
            TrainingSampleSecrecyHold h = intentHold(raw, recent);
            when(holds.findNextPending(any())).thenReturn(List.of(h));
            assertThat(rejudger.rejudgeOnce().rejected()).isEqualTo(1);
            verifyNoInteractions(intents);
        }

        @Test
        void releaseDeletesTheHoldBeforeWritingSoAFailedDeleteNeverRepeatsTheWrite() throws Exception {
            TrainingSampleSecrecyHold clean = intentHold("今天入库 350 箱", recent);
            when(holds.findNextPending(any())).thenReturn(List.of(clean));
            rejudger.rejudgeOnce();
            InOrder order = inOrder(holds, intents);
            order.verify(holds).delete(clean);
            order.verify(intents).recordSample(anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyDouble(), any());

            // a failing delete: the sample is NOT written (no re-record every 10 minutes)
            ExpressionLearningService intents2 = mock(ExpressionLearningService.class);
            TrainingSampleSecrecyHoldRepository holds2 = mock(TrainingSampleSecrecyHoldRepository.class);
            when(holds2.findNextPending(any())).thenReturn(List.of(clean));
            doThrow(new DataAccessResourceFailureException("down")).when(holds2).delete(clean);
            TrainingSampleHoldRejudger r2 = new TrainingSampleHoldRejudger(holds2, lists.filter, intents2, active,
                    Duration.ofDays(7), Clock.fixed(now, ZoneId.of("UTC")));
            try {
                r2.rejudgeOnce();
            } catch (RuntimeException expected) {
                // the delete failure surfaces; nothing was written
            }
            verifyNoInteractions(intents2);
        }

        @Test
        void stillUnjudgeableSamplesStayUntilTheyExpire() throws Exception {
            lists.fail.set(true);     // no last good lists either -> UNVERIFIED
            TrainingSampleSecrecyHold fresh = intentHold("今天入库", LocalDateTime.of(2026, 11, 2, 8, 0));
            TrainingSampleSecrecyHold old = intentHold("今天出库", LocalDateTime.of(2026, 10, 26, 7, 0));
            when(holds.findNextPending(any())).thenReturn(List.of(old, fresh));

            TrainingSampleHoldRejudger.Outcome o = rejudger.rejudgeOnce();

            assertThat(o).isEqualTo(new TrainingSampleHoldRejudger.Outcome(0, 0, 1, 1));
            verify(holds).delete(old);
            verify(holds).save(fresh);
            assertThat(fresh.getAttempts()).isEqualTo(1);
            assertThat(fresh.getLastAttemptAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0));
            verifyNoInteractions(intents, active);
        }

        @Test
        void unreadableHoldRowsStillExpire() {
            // review N4a: a payload that cannot be parsed must not stay held forever
            TrainingSampleSecrecyHold brokenOld = TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.INTENT,
                    FACTORY, "{not json", LocalDateTime.of(2026, 10, 20, 7, 0));
            TrainingSampleSecrecyHold brokenNew = TrainingSampleSecrecyHold.of(TrainingSampleSecrecyHold.Kind.INTENT,
                    FACTORY, "{not json", recent);
            when(holds.findNextPending(any())).thenReturn(List.of(brokenOld, brokenNew));

            TrainingSampleHoldRejudger.Outcome o = rejudger.rejudgeOnce();

            assertThat(o).isEqualTo(new TrainingSampleHoldRejudger.Outcome(0, 0, 1, 1));
            verify(holds).delete(brokenOld);
            verify(holds).save(brokenNew);
            assertThat(brokenNew.getLastAttemptAt()).isNotNull();       // pushed to the back of the queue (N4c)
        }

        @Test
        void activeLearningReleaseGoesThroughCollectSample() throws Exception {
            TrainingSampleSecrecyHold h = activeHold("那批");
            when(holds.findNextPending(any())).thenReturn(List.of(h));
            assertThat(rejudger.rejudgeOnce().released()).isEqualTo(1);
            verify(active).collectSample(FACTORY, 1L, "那批还剩多少", null, "X", new BigDecimal("0.3"),
                    "SEMANTIC", null, "那批", "rec-9");
            verify(holds).delete(h);
        }
    }

    @Test
    void holdStoreWritesOneRowAndNeverThrows() {
        TrainingSampleSecrecyHoldRepository repo = mock(TrainingSampleSecrecyHoldRepository.class);
        TrainingSampleHoldStore store = new TrainingSampleHoldStore(repo,
                Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"), ZoneId.of("UTC")));
        assertThat(store.holdIntent(FACTORY, new TrainingSampleHoldStore.IntentSample("今天入库", "X", "EXACT", 0.9, null)))
                .isTrue();
        ArgumentCaptor<TrainingSampleSecrecyHold> saved = ArgumentCaptor.forClass(TrainingSampleSecrecyHold.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getSampleKind()).isEqualTo(TrainingSampleSecrecyHold.Kind.INTENT);
        assertThat(saved.getValue().getReviewStatus()).isEqualTo(TrainingSampleSecrecyHold.ReviewStatus.PENDING);
        assertThat(saved.getValue().getPayload()).contains("今天入库");
        when(repo.save(any())).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(store.holdActiveLearning(FACTORY, new TrainingSampleHoldStore.ActiveLearningSampleData(
                1L, "x", null, null, null, null, null, null, null))).isFalse();
    }
}
