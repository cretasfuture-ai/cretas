package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.controller.SchedulingController;
import com.cretas.aims.dto.scheduling.SchedulingCapabilitiesDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.SchedulingServiceImpl;
import com.cretas.aims.service.scheduling.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SchedulingCapabilitiesContractTest {
    private static final String FACTORY = "F-CAP";
    @Mock private SchedulingPlanCrudService planCrudService;
    @Mock private LineScheduleService lineScheduleService;
    @Mock private WorkerAssignmentService workerAssignmentService;
    @Mock private SchedulingAIService aiService;
    @Mock private UrgentInsertService urgentInsertService;
    @Mock private LinUCBService linUCBService;
    @Mock private PermissionService permissionService;
    @Mock private FactoryConfigService configService;
    @Mock private UserModuleAccessService moduleAccessService;
    @Mock private UserRepository userRepository;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        SchedulingServiceImpl facade = new SchedulingServiceImpl(planCrudService, lineScheduleService,
                workerAssignmentService, aiService);
        mvc = MockMvcBuilders.standaloneSetup(new SchedulingController(facade, urgentInsertService, linUCBService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()),
                        new ModuleEnabledInterceptor(configService, moduleAccessService, userRepository, new ObjectMapper()))
                .build();
        user = new User();
        user.setId(901L);
        user.setFactoryId(FACTORY);
        user.setRoleCode("dispatcher");
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasAnyPermission(user, "scheduling:read", "production:read")).thenReturn(true);
        lenient().when(configService.isModuleEnabled(FACTORY, "scheduling")).thenReturn(true);
        lenient().when(moduleAccessService.canAccessModule(user, "scheduling")).thenReturn(true);
    }

    @Test
    void authorizedCapabilityReadReturnsDagGenerationUnavailableWorkersAndManualMode() throws Exception {
        mvc.perform(get("/api/mobile/" + FACTORY + "/scheduling/capabilities").requestAttr("userId", user.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contractVersion").value("scheduling-capabilities/v1"))
                // APS v1: 按工艺图 + 预计工时排; reason 必须把「缺工时不排」「不自动派工」说给用户
                .andExpect(jsonPath("$.data.aiGeneration.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.aiGeneration.mode").value("DAG_DURATION"))
                .andExpect(jsonPath("$.data.aiGeneration.reason").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("预计工时"),
                                org.hamcrest.Matchers.containsString("不排"),
                                org.hamcrest.Matchers.containsString("不自动派工"))))
                .andExpect(jsonPath("$.data.workerOptimization.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.workerOptimization.mode").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.reschedule.mode").value("RECORD_REASON_ONLY"))
                .andExpect(jsonPath("$.data.manualCreation.mode").value("EXPLICIT_SCHEDULES"));
        verify(permissionService).hasAnyPermission(user, "scheduling:read", "production:read");
        verify(moduleAccessService).canAccessModule(user, "scheduling");
    }

    @Test
    void capabilityReadRequiresLoginAndDoesNotCallBusinessServices() throws Exception {
        mvc.perform(get("/api/mobile/" + FACTORY + "/scheduling/capabilities"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(planCrudService, lineScheduleService, workerAssignmentService, aiService);
    }
}
