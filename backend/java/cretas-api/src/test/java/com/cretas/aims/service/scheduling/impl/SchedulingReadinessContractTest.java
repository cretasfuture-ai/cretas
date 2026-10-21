package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.controller.SchedulingDiagnosticsController;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.service.scheduling.SchedulingReadinessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SchedulingReadinessContractTest {
    @Mock ProductionLineRepository lines;
    @Mock ProductionBatchRepository batches;

    @Test
    void missingReferencesAreExplicitAndNeverDefaultToLineOrCapacity() {
        SchedulingReadinessResponseAssertions.assertIncomplete(new SchedulingReadinessService(lines, batches)
                .check("F-READINESS", null, null, null, null));
        verifyNoInteractions(lines, batches);
    }

    @Test
    void referencesAreValidatedWithinTenantAndImpossibleWindowIsNotProviderFailure() {
        ProductionLine line = new ProductionLine();
        lenient().when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-9", "F-READINESS"))
                .thenReturn(Optional.of(line));
        lenient().when(batches.findByIdAndFactoryId(9L, "F-READINESS"))
                .thenReturn(Optional.of(new ProductionBatch()));
        var result = new SchedulingReadinessService(lines, batches).check("F-READINESS", "LINE-9", 9L,
                LocalDateTime.of(2026, 10, 20, 10, 0), LocalDateTime.of(2026, 10, 20, 9, 0));
        assertThat(result.getStatus()).isEqualTo("INFEASIBLE");
        assertThat(result.getProbability().getOrigin()).isEqualTo("UNAVAILABLE");
        assertThat(result.getProductionLine().isConfirmed()).isFalse();
        verify(lines).findByIdAndFactoryIdAndDeletedAtIsNull("LINE-9", "F-READINESS");
        verify(batches).findByIdAndFactoryId(9L, "F-READINESS");
    }

    @Test
    void mountedControllerExposesReadOnlyTenantScopedContract() throws Exception {
        SchedulingReadinessService service = new SchedulingReadinessService(lines, batches);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SchedulingDiagnosticsController(service)).build();
        mvc.perform(get("/api/mobile/F-READINESS/scheduling/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.data.probability.optimization").value(false));
        verifyNoInteractions(lines, batches);
    }

    @Test
    void realSpringWiringAndRuntimeInterceptorsEnforceLoginTenantPermissionAndModule() throws Exception {
        var users = mock(com.cretas.aims.repository.UserRepository.class);
        var factories = mock(com.cretas.aims.repository.FactoryRepository.class);
        var permissions = mock(com.cretas.aims.service.PermissionService.class);
        var configuration = mock(com.cretas.aims.service.config.FactoryConfigService.class);
        var modules = mock(com.cretas.aims.service.UserModuleAccessService.class);
        var jwt = mock(com.cretas.aims.utils.JwtUtil.class);
        var blacklist = mock(com.cretas.aims.service.TokenBlacklistService.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var auth = new com.cretas.aims.config.JwtAuthInterceptor();
        org.springframework.test.util.ReflectionTestUtils.setField(auth, "jwtUtil", jwt);
        org.springframework.test.util.ReflectionTestUtils.setField(auth, "tokenBlacklistService", blacklist);
        org.springframework.test.util.ReflectionTestUtils.setField(auth, "factoryRepository", factories);
        org.springframework.test.util.ReflectionTestUtils.setField(auth, "userRepository", users);
        var user = new com.cretas.aims.entity.User();
        user.setId(902L); user.setFactoryId("F-READINESS"); user.setRoleCode("dispatcher"); user.setIsActive(true);
        var factory = new com.cretas.aims.entity.Factory(); factory.setId("F-READINESS"); factory.setIsActive(true);
        when(users.findById(902L)).thenReturn(Optional.of(user));
        when(factories.findById("F-READINESS")).thenReturn(Optional.of(factory));
        when(jwt.validateToken("readiness-test-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("readiness-test-token")).thenReturn(902L);
        when(jwt.getFactoryIdFromToken("readiness-test-token")).thenReturn("F-READINESS");
        when(jwt.getRoleFromToken("readiness-test-token")).thenReturn("dispatcher");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ProductionLineRepository.class, () -> lines);
            context.registerBean(ProductionBatchRepository.class, () -> batches);
            context.registerBean(SchedulingAIServiceImpl.class, () -> mock(SchedulingAIServiceImpl.class));
            context.registerBean(SchedulingReadinessService.class);
            context.registerBean(SchedulingDiagnosticsController.class);
            context.refresh();
            assertThat(context.getBean(SchedulingReadinessService.class)).isNotNull();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean(SchedulingDiagnosticsController.class))
                    .addInterceptors(auth,
                            new com.cretas.aims.config.PermissionInterceptor(permissions, users, mapper),
                            new com.cretas.aims.config.ModuleEnabledInterceptor(configuration, modules, users, mapper)).build();
            String url = "/api/mobile/F-READINESS/scheduling/readiness";
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/mobile/OTHER/scheduling/readiness").header("Authorization", "Bearer readiness-test-token"))
                    .andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", "Bearer readiness-test-token"))
                    .andExpect(status().isForbidden());
            when(permissions.hasAnyPermission(user, "scheduling:read", "production:read")).thenReturn(true);
            mvc.perform(get(url).header("Authorization", "Bearer readiness-test-token"))
                    .andExpect(status().isBadRequest());
            when(configuration.isModuleEnabled("F-READINESS", "scheduling")).thenReturn(true);
            when(modules.canAccessModule(user, "scheduling")).thenReturn(true);
            mvc.perform(get(url).header("Authorization", "Bearer readiness-test-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.readyForAutomation").value(false));
            verifyNoInteractions(lines, batches, context.getBean(SchedulingAIServiceImpl.class));
        }
    }

    @Test
    void completeTenantReferencesRemainUnconfirmedAndForeignReferencesAreRejected() {
        when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-9", "F-READINESS"))
                .thenReturn(Optional.of(new ProductionLine()));
        when(batches.findByIdAndFactoryId(9L, "F-READINESS"))
                .thenReturn(Optional.of(new ProductionBatch()));
        var complete = new SchedulingReadinessService(lines, batches).check("F-READINESS", "LINE-9", 9L,
                LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 20, 10, 0));
        assertThat(complete.getStatus()).isEqualTo("INPUTS_VALID_UNCONFIRMED");
        assertThat(complete.isReadyForAutomation()).isFalse();
        assertThat(complete.getProductionLine().isConfirmed()).isFalse();

        when(lines.findByIdAndFactoryIdAndDeletedAtIsNull("LINE-FOREIGN", "F-READINESS"))
                .thenReturn(Optional.empty());
        when(batches.findByIdAndFactoryId(99L, "F-READINESS")).thenReturn(Optional.empty());
        var foreign = new SchedulingReadinessService(lines, batches).check("F-READINESS", "LINE-FOREIGN", 99L,
                LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 20, 10, 0));
        assertThat(foreign.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(foreign.getMissingInputs()).contains("productionLineId:tenant_mismatch_or_missing", "batchId:tenant_mismatch_or_missing");
    }

    @Test
    void existingScheduleModeRejectsMixedResourceReferences() {
        var schedule = new com.cretas.aims.entity.LineSchedule();
        schedule.setProductionLineId("LINE-A"); schedule.setBatchId(7L);
        var result = new SchedulingReadinessService(lines, batches)
                .check("F-READINESS", "LINE-B", 8L, null, null, "S-7");
        assertThat(result.getRequestMode()).isEqualTo("EXISTING_SCHEDULE");
        assertThat(result.getScheduleId()).isEqualTo("S-7");
        assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(result.getMissingInputs()).contains("requestMode:mixed_schedule_and_explicit_inputs");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "INPUTS_INCOMPLETE,INCOMPLETE", "TENANT_SCOPED_RESOURCE_MISSING,INCOMPLETE",
            "INVALID_NUMERIC_INPUT,INCOMPLETE", "VERIFIED_MODEL_FEATURES_UNAVAILABLE,INCOMPLETE",
            "DETERMINISTIC_IMPOSSIBLE_WINDOW,INFEASIBLE", "COMPLETED,INPUTS_VALID_UNCONFIRMED"})
    void existingScheduleDiagnosticsDoNotReclassifyInvalidInputsAsComplete(String reason, String status) {
        var predictions = mock(SchedulingAIServiceImpl.class);
        var diagnostic = new com.cretas.aims.dto.scheduling.CompletionProbabilityResponse();
        diagnostic.setPredictionReason(reason);
        diagnostic.setPredictionOrigin("BUSINESS_INPUT");
        when(predictions.diagnoseCompletionProbability("F-READINESS", "S-7")).thenReturn(diagnostic);
        var service = new SchedulingReadinessService(lines, batches, predictions);
        var result = service.check("F-READINESS", null, null, null, null, "S-7");
        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(result.getTimeWindow().isValid()).isFalse();
        assertThat(result.getProductionLine().getSource()).isEqualTo("EXISTING_SCHEDULE_NOT_EXPOSED");
        assertThat(result.getProbability().getReason()).isEqualTo(reason);
        assertThat(result.getReason()).doesNotContain(reason);
        assertThat(result.isReadyForAutomation()).isFalse();
        verifyNoInteractions(lines, batches);
    }

    private static final class SchedulingReadinessResponseAssertions {
        static void assertIncomplete(com.cretas.aims.dto.scheduling.SchedulingReadinessResponse result) {
            assertThat(result.getStatus()).isEqualTo("INCOMPLETE");
            assertThat(result.getMissingInputs()).contains("productionLineId", "batchId", "timeWindow");
            assertThat(result.getProductionLine().getSource()).isEqualTo("MISSING_INPUT");
            assertThat(result.getProductionLine().getId()).isNull();
        }
    }
}
