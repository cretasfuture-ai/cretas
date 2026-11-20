package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.LinUCBService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.UrgentInsertService;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Executes the production permission interceptor and factory validator for the new write route. */
class SchedulingInsertPreviewPermissionGateContractTest {
    private static final String URL = "/api/mobile/F006/scheduling/urgent-insert/slots/SLOT/preview";
    private static final String BODY = """
            {"productTypeId":"PRODUCT","requiredQuantity":17,"quantityUnit":"PIECE","deadline":"%s"}
            """.formatted(java.time.LocalDateTime.now().plusDays(2).withNano(0));
    private UserRepository users;
    private PermissionService permissions;
    private UrgentInsertService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class); permissions = mock(PermissionService.class); service = mock(UrgentInsertService.class);
        User user = new User(); user.setId(1L); user.setFactoryId("F006"); user.setRoleCode("dispatcher"); user.setIsActive(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(service.createInsertPreview(eq("F006"), any(), eq("SLOT"), any())).thenReturn(Map.of("previewId", "server-preview"));
        SchedulingController controller = new SchedulingController(mock(SchedulingService.class), service, mock(LinUCBService.class));
        ReflectionTestUtils.setField(controller, "factoryAccessValidator", new FactoryAccessValidator(users));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new PermissionInterceptor(permissions, users, mapper)).build();
    }

    @Test
    void ownFactoryWithSchedulingWriteIssuesPreview() throws Exception {
        when(permissions.hasAnyPermission(any(User.class), eq(new String[]{"scheduling:read_write"}))).thenReturn(true);
        mvc.perform(post(URL).requestAttr("userId", 1L).requestAttr("role", "dispatcher")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.previewId").value("server-preview"));
        verify(service).createInsertPreview(eq("F006"), any(), eq("SLOT"), any());
    }

    @Test
    void readPermissionCannotCreatePreview() throws Exception {
        when(permissions.hasAnyPermission(any(User.class), eq(new String[]{"scheduling:read"}))).thenReturn(true);
        mvc.perform(post(URL).requestAttr("userId", 1L).requestAttr("role", "dispatcher")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void anotherFactoryIsRejectedBeforeIssuanceEvenWithWritePermission() throws Exception {
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(true);
        mvc.perform(post(URL.replace("F006", "OTHER")).requestAttr("userId", 1L).requestAttr("role", "dispatcher")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void missingIdentityCannotIssuePreview() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedCapacityUnitIsRejectedBeforeIssuance() throws Exception {
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(true);
        mvc.perform(post(URL).requestAttr("userId", 1L).requestAttr("role", "dispatcher")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("PIECE", "BOX")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
