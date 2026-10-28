package com.cretas.aims.service.aps;

import com.cretas.aims.controller.APSAdaptiveController;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.EfficiencyHistoryRepository;
import com.cretas.aims.repository.FactorySchedulingConfigRepository;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.PredictionModelWeightRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.WeightHistoryRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.aps.impl.APSAdaptiveSchedulingServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * APS 自适应接口的路径上带着 factoryId, 但原来一个都没用:
 * 按任务 id 直接 findById (能改别厂任务的进度和调整标记), 旧版看板 / 风险列表取全平台进行中的任务,
 * 全量重排把 null 传下去 (500), 重排结果编一个「+10%」。这里钉住按工厂取数和照实返回。
 */
class APSAdaptiveFactoryScopeContractTest {

    private final LineScheduleRepository schedules = mock(LineScheduleRepository.class);
    private final ProductionLineRepository lines = mock(ProductionLineRepository.class);
    private APSAdaptiveSchedulingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new APSAdaptiveSchedulingServiceImpl(schedules, lines, mock(EfficiencyHistoryRepository.class),
                mock(PredictionModelWeightRepository.class), mock(FactorySchedulingConfigRepository.class),
                mock(WeightHistoryRepository.class), new ObjectMapper());
        when(schedules.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());
        when(schedules.findByIdAndFactoryId("T-A", "F-A")).thenReturn(Optional.of(task("T-A")));
        // 两个任务按裸 id 都查得到 (T-B 属于别的厂): 只有按工厂取才分得出, 退回 findById 的实现会把 T-B 也改了
        when(schedules.findById("T-A")).thenReturn(Optional.of(task("T-A")));
        when(schedules.findById("T-B")).thenReturn(Optional.of(task("T-B")));
        when(schedules.findByFactoryIdAndStatus(eq("F-A"), eq(LineSchedule.ScheduleStatus.in_progress)))
                .thenReturn(List.of(task("T-A")));
    }

    private static LineSchedule task(String id) {
        LineSchedule t = new LineSchedule();
        t.setId(id);
        t.setStatus(LineSchedule.ScheduleStatus.in_progress);
        t.setPredictedCompletionProb(new java.math.BigDecimal("0.40"));
        return t;
    }

    // ---------- service ----------

    @Test
    void rescheduleOfAnotherFactorysTaskIs404AndChangesNothing() {
        assertThatThrownBy(() -> service.adaptiveReschedule("F-A", List.of("T-A", "T-B")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于本厂");
        verify(schedules, never()).save(any());
    }

    @Test
    void fullRescheduleTakesOnlyThisFactorysTasksAndSaysTimesWereNotChanged() {
        var result = service.adaptiveReschedule("F-A", List.of());

        assertThat(result.getScheduledTasks()).isEqualTo(1);
        assertThat(result.getMessage()).contains("未改动");
        verify(schedules).findByFactoryIdAndStatus("F-A", LineSchedule.ScheduleStatus.in_progress);
        verify(schedules, never()).findByStatus(any());
    }

    @Test
    void simulationInventsNoAfterNumbersAndRecommendsNothing() {
        var sim = service.simulateReschedule("F-A", List.of("T-A"));

        assertThat(sim.getAfterProbabilities()).isEmpty();
        assertThat(sim.isRecommendExecute()).isFalse();
    }

    @Test
    void riskListAndRescheduleCheckReadOnlyThisFactory() {
        when(lines.findByFactoryIdAndStatusAndDeletedAtIsNull("F-A", ProductionLine.LineStatus.maintenance))
                .thenReturn(List.of());

        service.getLowProbabilityTasks("F-A", 0.7);
        service.checkRescheduleNeed("F-A");
        service.getGlobalDashboard("F-A");

        verify(schedules, never()).findByStatus(any());
        verify(lines, never()).findByStatusAndDeletedAtIsNull(any());
        verify(lines, never()).findAll();
        verify(lines).findByFactoryIdAndDeletedAtIsNull("F-A");
    }

    @Test
    void ownershipChecks() {
        assertThat(service.taskBelongsToFactory("F-A", "T-A")).isTrue();
        assertThat(service.taskBelongsToFactory("F-A", "T-B")).isFalse();
        when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("L-A", "F-A")).thenReturn(Optional.of(new ProductionLine()));
        assertThat(service.lineBelongsToFactory("F-A", "L-A")).isTrue();
        assertThat(service.lineBelongsToFactory("F-A", "L-B")).isFalse();
    }

    // ---------- controller ----------

    private MockMvc controllerOver(APSAdaptiveSchedulingService svc) {
        return MockMvcBuilders.standaloneSetup(new APSAdaptiveController(svc, mock(DashboardAggregationService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void taskAndLineEndpointsRefuseAnotherFactorysIdsBeforeTouchingTheService() throws Exception {
        APSAdaptiveSchedulingService svc = mock(APSAdaptiveSchedulingService.class);
        when(svc.taskBelongsToFactory("F-A", "T-B")).thenReturn(false);
        when(svc.lineBelongsToFactory("F-A", "L-B")).thenReturn(false);
        MockMvc mvc = controllerOver(svc);

        mvc.perform(post("/api/mobile/F-A/aps/adaptive/tasks/T-B/progress")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"completedQty\":5}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/F-A/aps/adaptive/tasks/T-B/status")).andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/F-A/aps/adaptive/tasks/T-B/prediction")).andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/F-A/aps/adaptive/lines/L-B/status")).andExpect(status().isNotFound());

        verify(svc, never()).updateTaskProgress(anyString(), anyDouble(), any());
        verify(svc, never()).getTaskRealTimeStatus(anyString());
        verify(svc, never()).predictCompletionProbability(anyString());
        verify(svc, never()).getLineRealTimeStatus(anyString());
    }

    @Test
    void sharedStrategyWeightsCanOnlyBeAdjustedByAPlatformActor() throws Exception {
        APSAdaptiveSchedulingService svc = mock(APSAdaptiveSchedulingService.class);
        MockMvc mvc = controllerOver(svc);

        mvc.perform(post("/api/mobile/F-A/aps/adaptive/adjust-weights")
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 8L, "F-A", "factory_super_admin")))
                .andExpect(status().isForbidden());
        verify(svc, never()).autoAdjustStrategyWeights();
    }

    @Test
    void rescheduleResponseCarriesNoInventedImprovement() throws Exception {
        MockMvc mvc = controllerOver(service);

        mvc.perform(post("/api/mobile/F-A/aps/adaptive/reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"full\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rescheduledTasks").value(1))
                .andExpect(jsonPath("$.data.afterOnTimeRate").doesNotExist())
                .andExpect(jsonPath("$.data.improvementPercent").doesNotExist());
    }
}
