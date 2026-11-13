package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.DailyReconcileRun;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.DailyReconcileRunRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.scheduler.CostDeviationScanScheduler;
import com.cretas.aims.service.notification.NotificationService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 每日差异核对的真实入口: 调度器 → 运行器 → 扫描 → 建异常并按工厂配置派人 → 推送到负责人通知。
 * 链上除仓储 / 计算服务 / 通知出口外全是真对象。
 */
class DailyReconcileEndToEndContractTest {

    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
    private final ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
    private final PlanCostVarianceService varianceService = mock(PlanCostVarianceService.class);
    private final MaterialBalanceService balanceService = mock(MaterialBalanceService.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final CostDeviationCaseDetailRepository details = mock(CostDeviationCaseDetailRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final DailyReconcileRunRepository runs = mock(DailyReconcileRunRepository.class);
    private final List<DailyReconcileRun> savedRuns = new ArrayList<>();
    private final List<ShortageCase> savedCases = new ArrayList<>();

    private CostDeviationScanScheduler scheduler;

    @BeforeEach
    void wire() {
        when(factories.findAllActiveFactoryIds()).thenReturn(List.of("F1"));
        when(runs.save(any())).thenAnswer(i -> {
            savedRuns.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(cases.save(any())).thenAnswer(i -> {
            ShortageCase c = i.getArgument(0);
            if (c.getId() == null) c.setId("case-1");
            if (!savedCases.contains(c)) savedCases.add(c);
            return c;
        });
        // 第一次 (写入方) 没有未关闭实例, 之后 (推送方) 能读到刚建的
        when(cases.findOpenForUpdate(anyString(), any(), anyString())).thenAnswer(i ->
                savedCases.isEmpty() ? Optional.empty() : Optional.of(savedCases.get(0)));
        when(details.findClosedCases(any(), any(), any(), any())).thenReturn(List.of());
        CostDeviationCaseDetail saved = new CostDeviationCaseDetail();
        saved.setCaseId("case-1");
        saved.setPlanId("P1");
        saved.setPlanNumber("PL-1");
        saved.setMetricPct(new BigDecimal("9"));
        saved.setThresholdPct(new BigDecimal("5"));
        when(details.findById("case-1")).thenReturn(Optional.of(saved));
        when(users.findByIdAndFactoryId(7L, "F1")).thenReturn(Optional.of(new User()));
        when(settings.findCostDeviationOwnersByFactoryId("F1")).thenReturn(List.<Object[]>of(new Object[]{null, 7L, null, null}));
        ProductionBatch batch = new ProductionBatch();
        batch.setProductionPlanId("P1");
        when(batches.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any())).thenReturn(List.of(batch));
        when(varianceService.computeForPlan("F1", "P1", false)).thenReturn(PlanCostVarianceDTO.builder().planId("P1")
                .planNumber("PL-1").comparable(true).varianceRate(new BigDecimal("9")).totalVariance(new BigDecimal("120"))
                .incomparableItemCount(0).build());
        when(balanceService.table(anyString(), any(), any(), any())).thenReturn(
                MaterialBalanceTableDTO.builder().plans(List.of()).build());

        var resolver = new CostDeviationOwnerResolver(settings, users);
        var writer = new CostDeviationCaseWriter(cases, mock(ShortageCaseEventRepository.class), details, users, resolver);
        var notifier = new CostDeviationNotifier(cases, details, mock(ShortageCaseEventRepository.class), notifications);
        var scan = new CostDeviationScanService(settings, batches, varianceService, balanceService,
                mock(ProcessSecretLlmCostScope.class), writer, notifier, CostDeviationScanDefaults.none());
        scheduler = new CostDeviationScanScheduler(factories, new DailyReconcileRunner(scan, runs));
    }

    private void thresholds(Object variance, Object balance) {
        when(settings.findCostDeviationThresholdsByFactoryId("F1"))
                .thenReturn(List.<Object[]>of(new Object[]{variance, balance}));
    }

    @Test
    void dailyScan_createsCase_assignsConfiguredOwner_andNotifiesThem() {
        thresholds(new BigDecimal("5"), null);

        scheduler.scanConfiguredFactories();

        assertThat(savedCases).hasSize(1);
        assertThat(savedCases.get(0).getOwnerUserId()).isEqualTo(7L);   // 按工厂配置派人
        verify(notifications).notifyUser(eq1("F1"), eq1(7L), contains1("成本差异"), contains1("PL-1"));  // 推到负责人
        assertThat(savedRuns).hasSize(1);
        DailyReconcileRun run = savedRuns.get(0);
        assertThat(run.getTriggerSource()).isEqualTo("DAILY_SCAN");
        assertThat(run.getStatus()).isEqualTo(DailyReconcileRun.Status.PARTIAL); // 平衡阈值没配 → 如实标出
        assertThat(run.getCreatedCount()).isEqualTo(1);
        assertThat(run.getNotifiedCount()).isEqualTo(1);
        assertThat(run.getNotMeasuredReason()).contains("物料平衡阈值未配置");
    }

    @Test
    void dailyScan_roleOnlyConfig_notifiesRole_defaultFallsBackToCode() {
        thresholds(new BigDecimal("5"), null);
        when(settings.findCostDeviationOwnersByFactoryId("F1")).thenReturn(List.<Object[]>of(new Object[]{"quality_manager", null, null, null}));
        scheduler.scanConfiguredFactories();
        verify(notifications).notifyRole(eq1("F1"), eq1("quality_manager"), any(), any());
        assertThat(savedCases.get(0).getOwnerRole()).isEqualTo("quality_manager");

        savedCases.clear();
        when(settings.findCostDeviationOwnersByFactoryId("F1")).thenReturn(List.<Object[]>of(new Object[]{null, null, null, null}));
        scheduler.scanConfiguredFactories();
        assertThat(savedCases.get(0).getOwnerRole()).isEqualTo("finance_manager"); // 代码默认兜底
    }

    @Test
    void dailyScan_configuredOwnerNoLongerInFactory_fallsBackToDefaultRole() {
        thresholds(new BigDecimal("5"), null);
        when(users.findByIdAndFactoryId(7L, "F1")).thenReturn(Optional.empty()); // 配置的人已离厂 / 不属本厂

        scheduler.scanConfiguredFactories();

        assertThat(savedCases).hasSize(1);
        assertThat(savedCases.get(0).getOwnerUserId()).isNull();
        assertThat(savedCases.get(0).getOwnerRole()).isEqualTo("finance_manager");
        verify(notifications).notifyRole(eq1("F1"), eq1("finance_manager"), any(), any());
        verify(notifications, never()).notifyUser(any(), any(), any(), any());
        assertThat(new CostDeviationOwnerResolver(settings, users).resolve("F1", ShortageCase.SourceType.COST_VARIANCE).source())
                .isEqualTo("DEFAULT_USER_GONE");
    }

    @Test
    void dailyScan_oneFactoryScanThrows_othersStillRecorded() {
        when(factories.findAllActiveFactoryIds()).thenReturn(List.of("F1", "F2"));
        when(settings.findCostDeviationThresholdsByFactoryId("F1")).thenThrow(new IllegalStateException("boom"));
        when(settings.findCostDeviationThresholdsByFactoryId("F2")).thenReturn(List.<Object[]>of(new Object[]{null, null}));

        scheduler.scanConfiguredFactories();

        assertThat(savedRuns).extracting(DailyReconcileRun::getFactoryId).containsExactly("F1", "F2");
        assertThat(savedRuns.get(0).getStatus()).isEqualTo(DailyReconcileRun.Status.FAILED);
        assertThat(savedRuns.get(0).getNotMeasuredReason()).contains("boom");
        assertThat(savedRuns.get(1).getStatus()).isEqualTo(DailyReconcileRun.Status.NOT_MEASURED);
    }

    @Test
    void dailyScan_runRecordWriteFailsForOneFactory_othersStillRecorded() {
        when(factories.findAllActiveFactoryIds()).thenReturn(List.of("F1", "F2"));
        when(settings.findCostDeviationThresholdsByFactoryId(anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{null, null}));
        doAnswer(i -> {
            DailyReconcileRun r = i.getArgument(0);
            if ("F1".equals(r.getFactoryId())) throw new IllegalStateException("db down");
            savedRuns.add(r);
            return r;
        }).when(runs).save(any());

        scheduler.scanConfiguredFactories();

        assertThat(savedRuns).extracting(DailyReconcileRun::getFactoryId).containsExactly("F2");
    }

    @Test
    void scheduler_isLockedAcrossBlueGreenSlots() throws Exception {
        var m = CostDeviationScanScheduler.class.getMethod("scanConfiguredFactories");
        var lock = m.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("CostDeviationScanScheduler.scanConfiguredFactories");
    }

    @Test
    void dailyScan_noThresholds_recordsNotMeasured_neverNormal() {
        thresholds(null, null);

        scheduler.scanConfiguredFactories();

        assertThat(savedRuns).hasSize(1);
        DailyReconcileRun run = savedRuns.get(0);
        assertThat(run.getStatus()).isEqualTo(DailyReconcileRun.Status.NOT_MEASURED);
        assertThat(run.getNotMeasuredReason()).contains("没有配置").contains("没有扫描");
        assertThat(run.getEvaluatedCount()).isZero();
        verifyNoInteractions(varianceService, notifications);
        assertThat(savedCases).isEmpty();
    }

    @Test
    void dailyScan_configuredButNoPlans_isNotMeasured_notEmptyNormal() {
        thresholds(new BigDecimal("5"), null);
        when(batches.findByFactoryIdAndCreatedAtBetween(anyString(), any(), any())).thenReturn(List.of());

        scheduler.scanConfiguredFactories();

        assertThat(savedRuns.get(0).getStatus()).isEqualTo(DailyReconcileRun.Status.NOT_MEASURED);
        assertThat(savedRuns.get(0).getNotMeasuredReason()).contains("没有可评估的生产计划");
    }

    @Test
    void dailyScan_allPlansUnknown_isNotMeasured() {
        thresholds(new BigDecimal("5"), null);
        when(varianceService.computeForPlan("F1", "P1", false)).thenReturn(PlanCostVarianceDTO.builder().planId("P1")
                .comparable(false).comparabilityReason("没有快照").build());

        scheduler.scanConfiguredFactories();

        assertThat(savedRuns.get(0).getStatus()).isEqualTo(DailyReconcileRun.Status.NOT_MEASURED);
        assertThat(savedRuns.get(0).getUnknownCount()).isEqualTo(1);
        verifyNoInteractions(notifications);
    }

    @Test
    void dailyScan_notificationFailure_isCountedNotSilent() {
        thresholds(new BigDecimal("5"), null);
        doThrow(new IllegalStateException("db down")).when(notifications).notifyUser(any(), any(), any(), any());

        scheduler.scanConfiguredFactories();

        assertThat(savedCases).hasSize(1);   // 异常照建
        assertThat(savedRuns.get(0).getNotifyFailedCount()).isEqualTo(1);
        assertThat(savedRuns.get(0).getNotifiedCount()).isZero();
        assertThat(savedRuns.get(0).getNotMeasuredReason()).contains("推送失败");
    }

    @Test
    void defaults_whenSet_scanRunsForFactoriesWithoutOwnThresholds() {
        thresholds(null, null);
        var resolver = new CostDeviationOwnerResolver(settings, users);
        var writer = new CostDeviationCaseWriter(cases, mock(ShortageCaseEventRepository.class), details, users, resolver);
        var notifier = new CostDeviationNotifier(cases, details, mock(ShortageCaseEventRepository.class), notifications);
        var scan = new CostDeviationScanService(settings, batches, varianceService, balanceService,
                mock(ProcessSecretLlmCostScope.class), writer, notifier, new CostDeviationScanDefaults("5", ""));
        new DailyReconcileRunner(scan, runs).run("F1", "DAILY_SCAN");
        assertThat(savedRuns.get(0).getThresholdSource()).isEqualTo("DEFAULT");
        assertThat(savedRuns.get(0).getEvaluatedCount()).isEqualTo(1);
    }

    @Test
    void explanation_factsComeFromReadings_causesAreMarkedUnverified() {
        CostDeviationCaseDetail d = new CostDeviationCaseDetail();
        d.setPlanNumber("PL-1");
        d.setMetricPct(new BigDecimal("9.00"));
        d.setThresholdPct(new BigDecimal("5"));
        d.setAmountVariance(new BigDecimal("120.00"));
        PlanCostVarianceDTO v = PlanCostVarianceDTO.builder().priceVariance(new BigDecimal("80")).usageVariance(new BigDecimal("40"))
                .build();

        var e = CostDeviationExplanation.build(ShortageCase.SourceType.COST_VARIANCE, d, v, null, false);
        assertThat(e.knownFacts()).extracting(CostDeviationExplanation.Fact::text)
                .contains("成本差异率绝对值 9%, 阈值 5%", "价格差 80 元", "用量差 40 元");
        assertThat(e.knownFacts()).allMatch(f -> f.source() != null && f.source().contains("PL-1"));
        assertThat(e.possibleCauses()).isNotEmpty().allMatch(c -> !c.matches(".*\\d.*")); // 原因里没有数字
        assertThat(e.causeDisclaimer()).contains("没有验证");

        var masked = CostDeviationExplanation.build(ShortageCase.SourceType.COST_VARIANCE, d, v, null, true);
        assertThat(masked.knownFacts()).extracting(CostDeviationExplanation.Fact::text).noneMatch(t -> t.contains("元"));
    }

    // Mockito 的 eq/contains 取别名, 避免与 import static Mockito.* 的重名歧义
    private static <T> T eq1(T v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }

    private static String contains1(String s) {
        return org.mockito.ArgumentMatchers.contains(s);
    }
}
