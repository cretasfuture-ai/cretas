package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.controller.SchedulingController;
import com.cretas.aims.dto.scheduling.CompletionProbabilityResponse;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.SchedulingServiceImpl;
import com.cretas.aims.service.scheduling.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SchedulingProbabilityTenantBoundaryContractTest {
    private static final String FACTORY = "F-PROB";
    private static final String PLAN = "PLAN-PROB";
    private static final String SCHEDULE = "SCHEDULE-PROB";

    @Mock private SchedulingPlanRepository planRepository;
    @Mock private LineScheduleRepository scheduleRepository;
    @Mock private WorkerAssignmentRepository assignmentRepository;
    @Mock private ProductionLineRepository lineRepository;
    @Mock private SchedulingAlertRepository alertRepository;
    @Mock private SchedulingPredictionRepository predictionRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductionBatchRepository batchRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private DroolsRuleRepository droolsRuleRepository;
    @Mock private org.springframework.web.client.RestTemplate restTemplate;
    @Mock private FeatureEngineeringService featureEngineeringService;
    @Mock private NotificationService notificationService;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private SchedulingPlanCrudService planCrudService;
    @Mock private WorkerAssignmentService workerAssignmentService;
    @Mock private LineScheduleService lineScheduleService;
    @Mock private PermissionService permissionService;
    @Mock private FactoryConfigService configService;
    @Mock private UserModuleAccessService moduleAccessService;
    @Spy @InjectMocks private SchedulingAIServiceImpl aiService;

    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(aiService, "hybridPredictEnabled", false);
        SchedulingServiceImpl facade = new SchedulingServiceImpl(planCrudService,
                lineScheduleService, workerAssignmentService, aiService);
        mvc = MockMvcBuilders.standaloneSetup(new SchedulingController(facade,
                        mock(UrgentInsertService.class), mock(LinUCBService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()),
                        new ModuleEnabledInterceptor(configService, moduleAccessService, userRepository, new ObjectMapper()))
                .build();
        user = new User();
        user.setId(902L);
        user.setFactoryId(FACTORY);
        user.setRoleCode("dispatcher");
    }

    @Test
    void sameFactoryCompletedScheduleCanCalculateWithScopedMetadataAndWithoutModelCalls() {
        LineSchedule schedule = schedule(FACTORY);
        when(scheduleRepository.findById(SCHEDULE)).thenReturn(Optional.of(schedule));
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(FACTORY)));
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-PROB", FACTORY)).thenReturn(Optional.of(line()));

        CompletionProbabilityResponse response = aiService.calculateCompletionProbability(FACTORY, SCHEDULE);

        assertThat(response.getScheduleId()).isEqualTo(SCHEDULE);
        assertThat(response.getRemainingQuantity()).isZero();
        verify(lineRepository).findByIdAndFactoryIdAndDeletedAtIsNull("LINE-PROB", FACTORY);
        verifyNoInteractions(batchRepository, restTemplate, featureEngineeringService);
    }

    @Test
    void sameFactoryBatchValidatesPlanBeforeReadingSchedulesAndEachItemRemainsScoped() {
        LineSchedule schedule = schedule(FACTORY);
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(FACTORY)));
        when(scheduleRepository.findByPlanId(PLAN)).thenReturn(List.of(schedule));
        when(scheduleRepository.findById(SCHEDULE)).thenReturn(Optional.of(schedule));
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-PROB", FACTORY)).thenReturn(Optional.of(line()));

        List<CompletionProbabilityResponse> responses = aiService.calculateBatchProbabilities(FACTORY, PLAN);

        assertThat(responses).hasSize(1);
        verify(scheduleRepository).findByPlanId(PLAN);
        verify(planRepository, times(2)).findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY);
        verify(lineRepository).findByIdAndFactoryIdAndDeletedAtIsNull("LINE-PROB", FACTORY);
        verifyNoInteractions(batchRepository, restTemplate, featureEngineeringService);
    }

    @Test
    void foreignMissingAndDeletedParentsFailBeforeMetadataOrModelAccess() {
        LineSchedule schedule = schedule("F-OTHER");
        when(scheduleRepository.findById(SCHEDULE)).thenReturn(Optional.of(schedule));
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> aiService.calculateCompletionProbability(FACTORY, SCHEDULE))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> aiService.calculateBatchProbabilities(FACTORY, PLAN))
                .isInstanceOf(EntityNotFoundException.class);

        when(scheduleRepository.findById("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> aiService.calculateCompletionProbability(FACTORY, "MISSING"))
                .isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(lineRepository, batchRepository, restTemplate, featureEngineeringService);
        verify(scheduleRepository, never()).findByPlanId(anyString());
    }

    @Test
    void probabilityEndpointsRequireReadPermissionAndReachRealServiceWhenAuthorized() throws Exception {
        mvc.perform(get("/api/mobile/" + FACTORY + "/scheduling/schedules/" + SCHEDULE + "/probability"))
                .andExpect(status().isUnauthorized());
        permitUser(false);
        mvc.perform(get("/api/mobile/" + FACTORY + "/scheduling/schedules/" + SCHEDULE + "/probability")
                        .requestAttr("userId", user.getId()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(aiService);

        permitUser(true);
        when(scheduleRepository.findById(SCHEDULE)).thenReturn(Optional.of(schedule(FACTORY)));
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(FACTORY)));
        mvc.perform(get("/api/mobile/" + FACTORY + "/scheduling/schedules/" + SCHEDULE + "/probability")
                        .requestAttr("userId", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(permissionService, times(2)).hasAnyPermission(user, "scheduling:read", "production:read");
    }

    @Test
    void batchProbabilityEndpointAlsoRequiresReadPermission() throws Exception {
        String url = "/api/mobile/" + FACTORY + "/scheduling/plans/" + PLAN + "/probabilities";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        permitUser(false);
        mvc.perform(get(url).requestAttr("userId", user.getId()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(aiService);

        permitUser(true);
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY))
                .thenReturn(Optional.of(plan(FACTORY)));
        when(scheduleRepository.findByPlanId(PLAN)).thenReturn(List.of(schedule(FACTORY)));
        when(scheduleRepository.findById(SCHEDULE)).thenReturn(Optional.of(schedule(FACTORY)));
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-PROB", FACTORY)).thenReturn(Optional.of(line()));
        mvc.perform(get(url).requestAttr("userId", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void permitUser(boolean permission) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "scheduling:read", "production:read"))
                .thenReturn(permission);
        when(configService.isModuleEnabled(FACTORY, "scheduling")).thenReturn(true);
        when(moduleAccessService.canAccessModule(user, "scheduling")).thenReturn(true);
    }

    private static SchedulingPlan plan(String factoryId) {
        SchedulingPlan plan = new SchedulingPlan();
        plan.setId(PLAN);
        plan.setFactoryId(factoryId);
        return plan;
    }

    private static LineSchedule schedule(String factoryId) {
        LineSchedule schedule = new LineSchedule();
        schedule.setId(SCHEDULE);
        schedule.setPlanId(PLAN);
        schedule.setPlannedQuantity(10);
        schedule.setCompletedQuantity(10);
        schedule.setAssignedWorkers(1);
        schedule.setProductionLineId("LINE-PROB");
        return schedule;
    }

    private static ProductionLine line() {
        ProductionLine line = new ProductionLine();
        line.setId("LINE-PROB");
        line.setFactoryId(FACTORY);
        line.setStatus(ProductionLine.LineStatus.active);
        line.setName("Probability line");
        return line;
    }
}
