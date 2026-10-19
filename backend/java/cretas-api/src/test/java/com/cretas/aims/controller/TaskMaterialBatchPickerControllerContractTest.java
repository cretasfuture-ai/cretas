package com.cretas.aims.controller;

import com.cretas.aims.dto.production.TaskMaterialBatchOptionDTO;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.cretas.aims.service.production.TaskMaterialBatchPickerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TaskMaterialBatchPickerControllerContractTest {
    private static final String URL = "/api/mobile/F006/production/batches/10944/work-process-tasks/1989/material-options";
    @Mock private TaskMaterialBatchPickerService service;
    @Mock private PermissionService permissionService;
    @Mock private FactoryConfigService factoryConfigService;
    @Mock private UserModuleAccessService userModuleAccessService;
    @Mock private UserRepository userRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new TaskMaterialBatchPickerController(service))
                .addInterceptors(
                        new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()),
                        new ModuleEnabledInterceptor(factoryConfigService, userModuleAccessService,
                                userRepository, new ObjectMapper()))
                .build();
    }

    @Test
    void mountedRouteDelegatesWithFactoryBatchAndTaskContext() throws Exception {
        when(service.list("F006", 10944L, 1989L)).thenReturn(List.of(TaskMaterialBatchOptionDTO.builder()
                .id("MB-1").batchNumber("B-1").materialTypeId("MAT-1").currentQuantity(null)
                .quantityUnit("kg").warehouseId("WH-1").build()));
        allow(1686L);
        mvc.perform(get(URL).requestAttr("userId", 1686L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price").doesNotExist())
                .andExpect(jsonPath("$.data[0].cost").doesNotExist())
                .andExpect(jsonPath("$.data[0].supplier").doesNotExist());
        verify(service).list("F006", 10944L, 1989L);
    }

    @Test
    void missingProductionPermissionStopsBeforeService() throws Exception {
        allowUser(1686L);
        when(permissionService.hasAnyPermission(any(User.class), eq("production:read_write"), eq("production:read")))
                .thenReturn(false);
        mvc.perform(get(URL).requestAttr("userId", 1686L)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void disabledProductionModuleStopsBeforeService() throws Exception {
        allowUser(1686L);
        when(permissionService.hasAnyPermission(any(User.class), eq("production:read_write"), eq("production:read")))
                .thenReturn(true);
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(false);
        mvc.perform(get(URL).requestAttr("userId", 1686L)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unauthenticatedRequestStopsAtPermissionInterceptor() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private void allow(long userId) {
        allowUser(userId);
        when(permissionService.hasAnyPermission(any(User.class), eq("production:read_write"), eq("production:read")))
                .thenReturn(true);
        when(factoryConfigService.isModuleEnabled("F006", "production_plan")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(any(User.class), eq("production_plan"))).thenReturn(true);
    }

    private void allowUser(long userId) {
        User user = new User();
        user.setId(userId);
        user.setFactoryId("F006");
        user.setRoleCode("operator");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }
}
