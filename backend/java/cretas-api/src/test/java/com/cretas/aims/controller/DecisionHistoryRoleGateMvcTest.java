package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison.Status;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.DecisionSnapshotComparisonService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DecisionHistoryRoleGateMvcTest {
    private static final String URL = "/api/mobile/F006/decision-history/current/comparison?baselineId=baseline";
    private final FactoryAccessValidator access = mock(FactoryAccessValidator.class);
    private final DecisionSnapshotComparisonService comparison = mock(DecisionSnapshotComparisonService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private MockMvc mvc;
    private DecisionHistoryController controller;

    @BeforeEach void setup() {
        var mapper = new ObjectMapper();
        controller = new DecisionHistoryController(access, comparison);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissions, users, mapper),
                        new RequireRoleInterceptor(mapper)).build();
    }

    @Test void operatorCannotReadEvenWithGuessableIds() throws Exception {
        allowSystemRead();
        mvc.perform(get(URL).requestAttr("role", "operator").requestAttr("userId", 1L))
                .andExpect(status().isForbidden());
        verifyNoInteractions(access, comparison);
    }

    @Test void missingIdentityIsDeniedBeforeReadingAudit() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(access, comparison);
    }

    @Test void adminStillNeedsDynamicSystemReadPermission() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(false);
        mvc.perform(get(URL).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L))
                .andExpect(status().isForbidden());
        verifyNoInteractions(access, comparison);
    }

    @Test void authorizedReadChecksFactoryBeforeServiceAndReturnsNoExecution() throws Exception {
        allowSystemRead();
        when(comparison.compare("F006", "current", "baseline")).thenReturn(new DecisionSnapshotComparison(
                "current", "baseline", Status.SAME_RESULT, "RECORDED_OUTPUT_EQUAL", "a", "a",
                "rule", 2, "rule", 1, false, true,
                DecisionSnapshotComparison.RecordedOutputRelation.SAME, "PERSISTED_COLUMNS_SEMANTIC_ONLY"));
        mvc.perform(get(URL).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SAME_RESULT"))
                .andExpect(jsonPath("$.data.businessExecutionPerformed").value(false));
        var ordered = inOrder(access, comparison);
        ordered.verify(access).validateAccess("F006");
        ordered.verify(comparison).compare("F006", "current", "baseline");
        verify(permissions).hasAnyPermission(any(User.class), eq(new String[]{"system:read"}));
    }

    @Test void factoryValidationFailureNeverReachesSnapshotReader() {
        doThrow(new AuthorizationException("无权访问该工厂数据")).when(access).validateAccess("OTHER");
        assertThatThrownBy(() -> controller.compare("OTHER", "current", "baseline"))
                .isInstanceOf(AuthorizationException.class);
        verifyNoInteractions(comparison);
    }

    @Test void noPostBusinessExecutionRouteExists() throws Exception {
        mvc.perform(post(URL).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(access, comparison);
    }

    @Test void permissionAdminWithSystemReadAlsoPassesBothRealInterceptors() throws Exception {
        allowSystemRead();
        mvc.perform(get(URL).requestAttr("role", "permission_admin").requestAttr("userId", 1L))
                .andExpect(status().isOk());
        verify(access).validateAccess("F006");
        verify(comparison).compare("F006", "current", "baseline");
    }

    private void allowSystemRead() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(true);
    }

    private static User user() {
        var user = new User(); user.setId(1L); user.setFactoryId("F006"); return user;
    }
}
