package com.cretas.aims.service.discipline;

import com.cretas.aims.dto.WorkReportSubmitRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.impl.WorkReportingServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.util.BackdateWindowValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 A14: 补录窗口改为工厂级配置 (默认 2 天), 所有报工入口同一个校验。
 *
 * <p>阈值中心用 mock 模拟「工厂行 &gt; 全局行」: F_WIDE 配了 10 天, 其它工厂取全局 2 天。
 */
@DisplayName("A14 补录窗口: 工厂级配置 + 报工单入口接入")
class BackdateWindowFactoryConfigContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final String F_DEFAULT = "F001";
    private static final String F_WIDE = "F_WIDE";
    private static final String F_ZERO = "F_ZERO";

    private BackdateWindowValidator validator;

    @BeforeEach
    void setUp() {
        ThresholdResolverService thresholds = mock(ThresholdResolverService.class);
        // 全局默认行 = 2; F_WIDE 工厂行 = 10; F_ZERO 工厂行 = 0
        when(thresholds.getInteger(anyString(), eq(ThresholdKeys.PRODUCTION_BACKDATE_MAX_DAYS), anyInt()))
                .thenReturn(2);
        when(thresholds.getInteger(eq(F_WIDE), eq(ThresholdKeys.PRODUCTION_BACKDATE_MAX_DAYS), anyInt()))
                .thenReturn(10);
        when(thresholds.getInteger(eq(F_ZERO), eq(ThresholdKeys.PRODUCTION_BACKDATE_MAX_DAYS), anyInt()))
                .thenReturn(0);
        validator = new BackdateWindowValidator();
        // 全局属性兜底故意设成 7: 若校验器没读工厂配置, 下面的默认值断言 (T-3 拒绝) 会红
        ReflectionTestUtils.setField(validator, "maxDays", 7);
        ReflectionTestUtils.setField(validator, "disciplinePolicy", new FactoryDisciplinePolicy(thresholds));
    }

    @Test
    @DisplayName("默认 (全局行 2 天): T-2 放行, T-3 拒绝")
    void defaultTwoDays() {
        assertThatCode(() -> validator.assertWithinWindow(F_DEFAULT, TODAY.minusDays(2), "报工", TODAY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.assertWithinWindow(F_DEFAULT, TODAY.minusDays(3), "报工", TODAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getErrorCode()).isEqualTo("BACKDATE_WINDOW_EXCEEDED");
                    assertThat(e.getMessage()).contains("本厂补录窗口 2 天");
                });
    }

    @Test
    @DisplayName("非默认: 工厂配 10 天 → T-10 放行, T-11 拒绝; 阴性对照: 同一天对未配置的工厂仍拒绝")
    void factoryOverrideTenDays() {
        assertThatCode(() -> validator.assertWithinWindow(F_WIDE, TODAY.minusDays(10), "报工", TODAY))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.assertWithinWindow(F_WIDE, TODAY.minusDays(11), "报工", TODAY))
                .isInstanceOf(BusinessException.class);
        // 阴性对照: 一个工厂放宽不影响另一个工厂
        assertThatThrownBy(() -> validator.assertWithinWindow(F_DEFAULT, TODAY.minusDays(10), "报工", TODAY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("非默认: 工厂配 0 天 → 只能当天, T-1 拒绝")
    void factoryOverrideZero() {
        assertThatCode(() -> validator.assertWithinWindow(F_ZERO, TODAY, "报工", TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.assertWithinWindow(F_ZERO, TODAY.minusDays(1), "报工", TODAY))
                .isInstanceOf(BusinessException.class);
    }

    // ── 报工单入口 (WorkReportingServiceImpl) 原来不过窗口 ─────────────────────

    private WorkReportingServiceImpl workReporting(ProductionReportRepository repo) {
        return new WorkReportingServiceImpl(repo, mock(BatchWorkSessionRepository.class),
                mock(ProductionBatchRepository.class), mock(UserRepository.class),
                mock(ApplicationEventPublisher.class), mock(UnitContractService.class), validator);
    }

    private static WorkReportSubmitRequest hoursReport(LocalDate date) {
        return WorkReportSubmitRequest.builder().reportType("HOURS").totalWorkMinutes(60).reportDate(date).build();
    }

    @Test
    @DisplayName("报工单提交: 超出本厂窗口 → 409 且不落库; 阴性对照: 窗口内照常落库")
    void submitReportUsesFactoryWindow() {
        ProductionReportRepository repo = mock(ProductionReportRepository.class);
        when(repo.save(any(ProductionReport.class))).thenAnswer(inv -> inv.getArgument(0));
        WorkReportingServiceImpl svc = workReporting(repo);

        LocalDate tooOld = LocalDate.now().minusDays(5);
        assertThatThrownBy(() -> svc.submitReport(F_DEFAULT, 9L, hoursReport(tooOld)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("BACKDATE_WINDOW_EXCEEDED"));
        verify(repo, never()).save(any());

        // 同样的 T-5, 配了 10 天的工厂可以提交
        svc.submitReport(F_WIDE, 9L, hoursReport(tooOld));
        verify(repo).save(any(ProductionReport.class));
    }

    @Test
    @DisplayName("报工单修改: 把日期改到窗口外 → 409; 阴性对照: 不改日期照常保存")
    void updateReportUsesFactoryWindow() {
        ProductionReportRepository repo = mock(ProductionReportRepository.class);
        ProductionReport existing = ProductionReport.builder().id(1L).factoryId(F_DEFAULT)
                .reportType("HOURS").reportDate(LocalDate.now()).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(ProductionReport.class))).thenAnswer(inv -> inv.getArgument(0));
        WorkReportingServiceImpl svc = workReporting(repo);

        assertThatThrownBy(() -> svc.updateReport(F_DEFAULT, 1L, hoursReport(LocalDate.now().minusDays(5))))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());

        svc.updateReport(F_DEFAULT, 1L, WorkReportSubmitRequest.builder().totalWorkMinutes(90).build());
        verify(repo).save(any(ProductionReport.class));
        assertThat(existing.getReportDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("报工单修改: 4 天前未审批的报工, 原样回传旧日期只改工时 → 照常保存 (日期没变不算补录); 阴性对照: 改到另一个窗口外日期仍 409")
    void updateKeepingAnOldDateIsNotABackfill() {
        ProductionReportRepository repo = mock(ProductionReportRepository.class);
        LocalDate fourDaysAgo = LocalDate.now().minusDays(4);
        ProductionReport existing = ProductionReport.builder().id(2L).factoryId(F_DEFAULT)
                .reportType("HOURS").reportDate(fourDaysAgo).build();
        when(repo.findById(2L)).thenReturn(Optional.of(existing));
        when(repo.save(any(ProductionReport.class))).thenAnswer(inv -> inv.getArgument(0));
        WorkReportingServiceImpl svc = workReporting(repo);

        svc.updateReport(F_DEFAULT, 2L, WorkReportSubmitRequest.builder()
                .reportDate(fourDaysAgo).totalWorkMinutes(90).build());
        verify(repo).save(any(ProductionReport.class));
        assertThat(existing.getTotalWorkMinutes()).isEqualTo(90);

        assertThatThrownBy(() -> svc.updateReport(F_DEFAULT, 2L, WorkReportSubmitRequest.builder()
                .reportDate(fourDaysAgo.minusDays(1)).build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("BACKDATE_WINDOW_EXCEEDED"));
    }
}
