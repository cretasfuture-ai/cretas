package com.cretas.aims.service;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.consent.TrainingConsentService;
import com.cretas.aims.service.consent.TrainingConsentService.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-29 MOCK_REST AI writes stopped, owner-approved: 定时日报 / 周报 / 月报跳过
 * 排除清单里的工厂和训练授权已撤回的工厂; 正常工厂照常生成 (阴性对照);
 * 授权查不到 / 查询抛异常时照常生成, 不中断其它工厂。
 *
 * <p>测试走的是 {@code @Scheduled} 方法本身 (生产入口), 不是只调过滤函数。
 */
@ExtendWith(MockitoExtension.class)
class AIReportSchedulerExclusionTest {

    @Mock
    private AIEnterpriseService aiEnterpriseService;
    @Mock
    private FactoryRepository factoryRepository;
    @Mock
    private TrainingConsentService trainingConsentService;

    @InjectMocks
    private AIReportScheduler scheduler;

    private static Factory factory(String id) {
        Factory f = new Factory();
        f.setId(id);
        return f;
    }

    @Test
    void dailyReport_skipsListedFactory_evenWhenConsentIsNormal_andStillReportsNormalFactory() {
        when(factoryRepository.findByIsActiveTrue()).thenReturn(List.of(factory("MOCK_REST"), factory("F006")));
        when(trainingConsentService.status("F006")).thenReturn(Status.DEFAULT);

        scheduler.generateDailyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateDailyReport("MOCK_REST");
        verify(aiEnterpriseService, times(1)).generateDailyReport("F006");
    }

    @Test
    void dailyReport_skipsWithdrawnFactory_whenExclusionListIsEmpty() {
        ReflectionTestUtils.setField(scheduler, "excludedFactories", "");
        when(factoryRepository.findByIsActiveTrue()).thenReturn(List.of(factory("MOCK_REST"), factory("F006")));
        when(trainingConsentService.status("MOCK_REST")).thenReturn(Status.WITHDRAWN);
        when(trainingConsentService.status("F006")).thenReturn(Status.AUTHORIZED);

        scheduler.generateDailyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateDailyReport("MOCK_REST");
        verify(aiEnterpriseService, times(1)).generateDailyReport("F006");
    }

    @Test
    void dailyReport_consentLookupFailure_doesNotStopOtherFactories() {
        ReflectionTestUtils.setField(scheduler, "excludedFactories", "");
        when(factoryRepository.findByIsActiveTrue()).thenReturn(
                List.of(factory("F006"), factory("MOCK_REST"), factory("DEMO_FACTORY2")));
        when(trainingConsentService.status("F006")).thenThrow(new IllegalStateException("smartbi down"));
        when(trainingConsentService.status("MOCK_REST")).thenReturn(Status.WITHDRAWN);
        when(trainingConsentService.status("DEMO_FACTORY2")).thenReturn(Status.UNKNOWN);

        scheduler.generateDailyReportsForAllFactories();

        // 查询抛异常 / UNKNOWN: 照常生成 (不让 SmartBI 一挂所有工厂日报停掉)
        verify(aiEnterpriseService, times(1)).generateDailyReport("F006");
        verify(aiEnterpriseService, times(1)).generateDailyReport("DEMO_FACTORY2");
        // 抛异常的那家之后, 撤回的那家仍被正确跳过 (循环没被打断, 也没把异常当成放行全部)
        verify(aiEnterpriseService, never()).generateDailyReport("MOCK_REST");
    }

    @Test
    void weeklyReport_skipsListedAndWithdrawn_andReportsNormalFactory() {
        when(factoryRepository.findByIsActiveTrue()).thenReturn(
                List.of(factory("MOCK_REST"), factory("OTHER_WITHDRAWN"), factory("F006")));
        when(trainingConsentService.status("OTHER_WITHDRAWN")).thenReturn(Status.WITHDRAWN);
        when(trainingConsentService.status("F006")).thenReturn(Status.DEFAULT);

        scheduler.generateWeeklyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateWeeklyReport(eq("MOCK_REST"), any(LocalDate.class), any(LocalDate.class));
        verify(aiEnterpriseService, never()).generateWeeklyReport(eq("OTHER_WITHDRAWN"), any(LocalDate.class), any(LocalDate.class));
        verify(aiEnterpriseService, times(1)).generateWeeklyReport(eq("F006"), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void monthlyReport_skipsListedAndWithdrawn_andReportsNormalFactory() {
        when(factoryRepository.findByIsActiveTrue()).thenReturn(
                List.of(factory("MOCK_REST"), factory("OTHER_WITHDRAWN"), factory("F006")));
        when(trainingConsentService.status("OTHER_WITHDRAWN")).thenReturn(Status.WITHDRAWN);
        when(trainingConsentService.status("F006")).thenReturn(Status.DEFAULT);

        scheduler.generateMonthlyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateMonthlyReport(eq("MOCK_REST"), any(LocalDate.class), any(LocalDate.class));
        verify(aiEnterpriseService, never()).generateMonthlyReport(eq("OTHER_WITHDRAWN"), any(LocalDate.class), any(LocalDate.class));
        verify(aiEnterpriseService, times(1)).generateMonthlyReport(eq("F006"), any(LocalDate.class), any(LocalDate.class));
    }

    /** 生产上的默认值来自 @Value 占位符, 不是字段初始值; 钉住它默认就是 MOCK_REST。 */
    @Test
    void exclusionListDefaultsToMockRestInSpringPlaceholder() throws NoSuchFieldException {
        Value value = AIReportScheduler.class.getDeclaredField("excludedFactories").getAnnotation(Value.class);
        assertThat(value).isNotNull();
        assertThat(value.value()).isEqualTo("${cretas.ai.report.excluded-factories:MOCK_REST}");
    }
}
