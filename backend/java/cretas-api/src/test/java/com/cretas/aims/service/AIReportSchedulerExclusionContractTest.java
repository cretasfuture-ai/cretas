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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-29 MOCK_REST AI writes stopped, owner-approved: 定时日报 / 周报 / 月报只跳过排除清单
 * {@code cretas.ai.report.excluded-factories} (默认 MOCK_REST) 里的工厂。
 *
 * <p>⛔ 不按训练授权判 (Steve 2026-10-24: 训练授权只管训练样本; 经营报告是服务, 不是训练样本)。
 * 这里故意放一个对所有工厂都回 WITHDRAWN 的 {@link TrainingConsentService} 桩: 谁把「撤回就跳过」
 * 加回调度器 (字段注入会被 {@code @InjectMocks} 填上这个桩), 撤回授权工厂那条阴性对照就红。
 *
 * <p>测试走的是 {@code @Scheduled} 方法本身 (生产入口), 不是只调过滤函数。
 */
@ExtendWith(MockitoExtension.class)
class AIReportSchedulerExclusionContractTest {

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

    private void everyFactoryHasWithdrawnTrainingConsent() {
        lenient().when(trainingConsentService.status(anyString())).thenReturn(Status.WITHDRAWN);
    }

    @Test
    void dailyReport_skipsMockRestByDefault_andStillReportsNormalFactory() {
        when(factoryRepository.findByIsActiveTrue()).thenReturn(List.of(factory("MOCK_REST"), factory("F006")));

        scheduler.generateDailyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateDailyReport("MOCK_REST");
        verify(aiEnterpriseService, times(1)).generateDailyReport("F006");
    }

    /** 阴性对照: 训练授权撤回但不在清单里的工厂, 报告照常 (报告不是训练样本)。 */
    @Test
    void dailyReport_withdrawnTrainingConsentButNotListed_stillGetsReport() {
        everyFactoryHasWithdrawnTrainingConsent();
        when(factoryRepository.findByIsActiveTrue()).thenReturn(
                List.of(factory("MOCK_REST"), factory("CONSENT_WITHDRAWN_FACTORY")));

        scheduler.generateDailyReportsForAllFactories();

        verify(aiEnterpriseService, times(1)).generateDailyReport("CONSENT_WITHDRAWN_FACTORY");
        verify(aiEnterpriseService, never()).generateDailyReport("MOCK_REST");
    }

    /** 清单就是开关: 设为空串, MOCK_REST 也照常出报告。 */
    @Test
    void dailyReport_emptyExclusionList_excludesNobody() {
        ReflectionTestUtils.setField(scheduler, "excludedFactories", "");
        when(factoryRepository.findByIsActiveTrue()).thenReturn(List.of(factory("MOCK_REST")));

        scheduler.generateDailyReportsForAllFactories();

        verify(aiEnterpriseService, times(1)).generateDailyReport("MOCK_REST");
    }

    @Test
    void dailyReport_configuredListIsTrimmedAndCommaSeparated() {
        ReflectionTestUtils.setField(scheduler, "excludedFactories", " A_FACTORY , ,B_FACTORY ");
        when(factoryRepository.findByIsActiveTrue()).thenReturn(
                List.of(factory("A_FACTORY"), factory("B_FACTORY"), factory("F006")));

        scheduler.generateDailyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateDailyReport("A_FACTORY");
        verify(aiEnterpriseService, never()).generateDailyReport("B_FACTORY");
        verify(aiEnterpriseService, times(1)).generateDailyReport("F006");
    }

    @Test
    void weeklyReport_skipsListed_andReportsWithdrawnConsentFactory() {
        everyFactoryHasWithdrawnTrainingConsent();
        when(factoryRepository.findByIsActiveTrue()).thenReturn(List.of(factory("MOCK_REST"), factory("F006")));

        scheduler.generateWeeklyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateWeeklyReport(eq("MOCK_REST"), any(LocalDate.class), any(LocalDate.class));
        verify(aiEnterpriseService, times(1)).generateWeeklyReport(eq("F006"), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void monthlyReport_skipsListed_andReportsWithdrawnConsentFactory() {
        everyFactoryHasWithdrawnTrainingConsent();
        when(factoryRepository.findByIsActiveTrue()).thenReturn(List.of(factory("MOCK_REST"), factory("F006")));

        scheduler.generateMonthlyReportsForAllFactories();

        verify(aiEnterpriseService, never()).generateMonthlyReport(eq("MOCK_REST"), any(LocalDate.class), any(LocalDate.class));
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
