package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.production.ProductionReportCorrectionDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionReportCorrectionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductionReportCorrectionPermissionContractTest {
    private static final String URL = "/api/mobile/F006/production-report-corrections/reports/23936";
    private static final String BODY = "{\"outputQuantity\":1,\"notes\":\"permission gate\","
            + "\"reason\":\"permission contract\"}";

    @Mock private ProductionReportCorrectionService correctionService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private FactoryModuleConfigRepository factoryModuleConfigRepository;
    @Mock private PlatformRolePermissionRepository platformRolePermissionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductionReportCorrectionController controller =
                new ProductionReportCorrectionController(correctionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(
                        permissionService, userRepository, new ObjectMapper()))
                .build();
    }

    @Test
    void operatorWithoutDedicatedPermissionGets403BeforeDraftIsCreated() throws Exception {
        User operator = user(1311L, "operator");
        when(userRepository.findById(1311L)).thenReturn(Optional.of(operator));
        when(permissionService.hasAnyPermission(operator,
                "production_report_correction:read_write")).thenReturn(false);

        mockMvc.perform(post(URL)
                        .requestAttr("userId", 1311L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.meta.module").value("production_report_correction"));

        verify(correctionService, never()).submit(any(), any(), any(), any());
    }

    @Test
    void configuredProductionManagerReachesSubmitServiceThroughSameInterceptor() throws Exception {
        User manager = user(1310L, "production_manager");
        when(userRepository.findById(1310L)).thenReturn(Optional.of(manager));
        when(permissionService.hasAnyPermission(manager,
                "production_report_correction:read_write")).thenReturn(true);
        when(correctionService.submit(eq("F006"), eq(23936L), any(), eq(1310L)))
                .thenReturn(ProductionReportCorrectionDTO.builder().id("C-ALLOWED").build());

        mockMvc.perform(post(URL)
                        .requestAttr("userId", 1310L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("C-ALLOWED"));

        verify(correctionService).submit(eq("F006"), eq(23936L), any(), eq(1310L));
    }

    @Test
    void realPermissionResolutionDeniesOperatorByDefaultAndAcceptsConfiguredManagerOverride() {
        PermissionServiceImpl realPermissionService = new PermissionServiceImpl();
        ReflectionTestUtils.setField(realPermissionService, "factoryConfigRepo",
                factoryModuleConfigRepository);
        ReflectionTestUtils.setField(realPermissionService, "platformRepo",
                platformRolePermissionRepository);

        FactoryModuleConfig config = FactoryModuleConfig.builder()
                .factoryId("F006")
                .moduleCode("__permissions__")
                .configVersion(1)
                .roleModuleOverride(Map.of(
                        "production_manager", Map.of("production_report_correction", "rw")))
                .build();
        when(factoryModuleConfigRepository.findByFactoryIdAndConfigVersion("F006", 1))
                .thenReturn(List.of(config));
        when(platformRolePermissionRepository
                .findByRoleCodeAndModuleCodeAndDeletedAtIsNull(any(), eq("production_report_correction")))
                .thenReturn(Optional.empty());

        assertThat(realPermissionService.hasPermission(user(1311L, "operator"),
                "production_report_correction:read_write")).isFalse();
        assertThat(realPermissionService.hasPermission(user(1310L, "production_manager"),
                "production_report_correction:read_write")).isTrue();
    }

    private static User user(Long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId("F006");
        user.setRoleCode(roleCode);
        return user;
    }
}
