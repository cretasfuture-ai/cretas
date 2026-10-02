package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.controller.SchedulingController;
import com.cretas.aims.dto.scheduling.OptimizeWorkersRequest;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real optimizer -> facade -> controller/advice; repository and policy boundaries are isolated. */
@ExtendWith(MockitoExtension.class)
class SchedulingWorkerOptimizationUnavailableContractTest {
    private static final String FACTORY = "F-B9";
    private static final String PLAN = "PLAN-B9";
    private static final String MESSAGE = "自动人员优化暂不可用，本次未调整派工，请手动指派";
    private static final String URL = "/api/mobile/" + FACTORY + "/scheduling/optimize-workers";
    private static final String BODY = "{\"planId\":\"" + PLAN + "\"}";

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
    @Mock private PermissionService permissionService;
    @Mock private FactoryConfigService configService;
    @Mock private UserModuleAccessService moduleAccessService;
    @InjectMocks private SchedulingAIServiceImpl optimizer;

    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(optimizer, "schedulingPlanCrudService", planCrudService);
        ReflectionTestUtils.setField(optimizer, "workerAssignmentService", workerAssignmentService);
        SchedulingServiceImpl facade = new SchedulingServiceImpl(planCrudService,
                mock(LineScheduleService.class), workerAssignmentService, optimizer);
        SchedulingController controller = new SchedulingController(facade,
                mock(UrgentInsertService.class), mock(LinUCBService.class));
        ObjectMapper mapper = new ObjectMapper();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, mapper),
                        new ModuleEnabledInterceptor(configService, moduleAccessService, userRepository, mapper))
                .build();
        user = new User();
        user.setId(900L);
        user.setFactoryId(FACTORY);
        user.setRoleCode("dispatcher");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cost", "efficiency", "balanced"})
    void validPlanExplicitlyRejectsUnavailableOptimizationWithoutAssignmentReadsOrWrites(String goal) {
        existingPlan();
        OptimizeWorkersRequest request = request(PLAN);
        request.setOptimizationGoal(goal);

        BusinessException error = assertThrows(BusinessException.class,
                () -> optimizer.optimizeWorkers(FACTORY, request));

        assertThat(error.getCode()).isEqualTo(400);
        assertThat(error.getMessage()).isEqualTo(MESSAGE);
        onlyScopedPlanLookup(FACTORY, PLAN);
    }

    @ParameterizedTest
    @CsvSource({"F-B9,UNKNOWN", "OTHER-FACTORY,PLAN-B9"})
    void missingOrForeignPlanPreservesScopedNotFoundBeforeUnavailableError(String factory, String planId) {
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(planId, factory))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> optimizer.optimizeWorkers(factory, request(planId)));

        onlyScopedPlanLookup(factory, planId);
    }

    @Test
    void authorizedMvcRequestReachesRealOptimizerAndReturnsFailureThroughRealAdvice() throws Exception {
        authorize();
        existingPlan();

        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andExpect(jsonPath("$.data").isEmpty());

        onlyScopedPlanLookup(FACTORY, PLAN);
        verify(permissionService).hasAnyPermission(user, "scheduling:read_write", "production:read_write");
        verify(moduleAccessService).canAccessModule(user, "scheduling");
    }

    @Test
    void mvcUnknownPlanStillReturns404() throws Exception {
        authorize();
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull("UNKNOWN", FACTORY))
                .thenReturn(Optional.empty());

        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planId\":\"UNKNOWN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(404));

        onlyScopedPlanLookup(FACTORY, "UNKNOWN");
    }

    @Test
    void missingLoginIsRejectedBeforeOptimizer() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        noOptimizationInteraction();
    }

    @Test
    void missingWritePermissionIsRejectedBeforeOptimizer() throws Exception {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.module").value("scheduling"));
        verify(permissionService).hasAnyPermission(user, "scheduling:read_write", "production:read_write");
        noOptimizationInteraction();
    }

    @Test
    void disabledModuleIsRejectedBeforeOptimizer() throws Exception {
        permit();
        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("模块 scheduling 未启用"));
        noOptimizationInteraction();
    }

    @Test
    void accountFromAnotherFactoryIsRejectedBeforeOptimizer() throws Exception {
        permit();
        user.setFactoryId("OTHER-FACTORY");
        when(configService.isModuleEnabled(FACTORY, "scheduling")).thenReturn(true);
        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.factoryId").value("OTHER-FACTORY"));
        verifyNoInteractions(moduleAccessService);
        noOptimizationInteraction();
    }

    @Test
    void accountWithoutModuleAccessIsRejectedBeforeOptimizer() throws Exception {
        permit();
        when(configService.isModuleEnabled(FACTORY, "scheduling")).thenReturn(true);
        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.meta.module").value("scheduling"));
        verify(moduleAccessService).canAccessModule(user, "scheduling");
        noOptimizationInteraction();
    }

    @Test
    void mvcMissingPlanIdStillFailsBeanValidationBeforeOptimizer() throws Exception {
        authorize();
        mvc.perform(post(URL).requestAttr("userId", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        noOptimizationInteraction();
    }

    private void permit() {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(permissionService.hasAnyPermission(user, "scheduling:read_write", "production:read_write"))
                .thenReturn(true);
    }

    private void authorize() {
        permit();
        when(configService.isModuleEnabled(FACTORY, "scheduling")).thenReturn(true);
        when(moduleAccessService.canAccessModule(user, "scheduling")).thenReturn(true);
    }

    private void existingPlan() {
        SchedulingPlan plan = new SchedulingPlan();
        plan.setId(PLAN);
        plan.setFactoryId(FACTORY);
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull(PLAN, FACTORY))
                .thenReturn(Optional.of(plan));
    }

    private static OptimizeWorkersRequest request(String planId) {
        OptimizeWorkersRequest request = new OptimizeWorkersRequest();
        request.setPlanId(planId);
        return request;
    }

    private void onlyScopedPlanLookup(String factory, String planId) {
        verify(planRepository).findByIdAndFactoryIdAndDeletedAtIsNull(planId, factory);
        verifyNoMoreInteractions(planRepository);
        noAssignmentInteraction();
    }

    private void noOptimizationInteraction() {
        verifyNoInteractions(planRepository);
        noAssignmentInteraction();
    }

    private void noAssignmentInteraction() {
        verifyNoInteractions(scheduleRepository, assignmentRepository, workerAssignmentService,
                planCrudService, lineRepository, alertRepository, predictionRepository,
                batchRepository, productionPlanRepository, droolsRuleRepository, restTemplate,
                featureEngineeringService, notificationService, pushNotificationService);
    }
}
