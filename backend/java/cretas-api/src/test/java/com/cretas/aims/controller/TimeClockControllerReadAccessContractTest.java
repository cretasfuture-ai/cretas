package com.cretas.aims.controller;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TimeClockService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TimeClockController 的真实 MVC 读权限契约。
 *
 * <p>跨员工/聚合读端点必须穿过真实 PermissionInterceptor +
 * ModuleEnabledInterceptor；员工个人状态、历史、今日、统计和月度查询保持 JWT 自读。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimeClockController read access (MockMvc + real interceptors)")
class TimeClockControllerReadAccessContractTest {

    private static final String FACTORY = "F006";
    private static final String START = "2026-10-01";
    private static final String END = "2026-10-04";
    private static final byte[] EXPORT_BYTES = new byte[]{'x', 'l', 's', 'x'};

    @Mock private TimeClockService timeClockService;
    @Mock private PermissionService permissionService;
    @Mock private UserModuleAccessService userModuleAccessService;
    @Mock private FactoryConfigService factoryConfigService;
    @Mock private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mvcFor(permissionService);
    }

    private MockMvc mvcFor(PermissionService canonicalPermissions) {
        TimeClockController controller = new TimeClockController(timeClockService);
        PermissionInterceptor permissionInterceptor =
                new PermissionInterceptor(canonicalPermissions, userRepository, new ObjectMapper());
        ModuleEnabledInterceptor moduleInterceptor = new ModuleEnabledInterceptor(
                factoryConfigService,
                userModuleAccessService,
                userRepository,
                new ObjectMapper());
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(permissionInterceptor, moduleInterceptor)
                .build();
    }

    @ParameterizedTest(name = "missing canonical HR permission rejects {0}")
    @MethodSource("aggregateUris")
    void aggregateDeniedWithoutCanonicalHrRead(String uri) throws Exception {
        User actor = user(10L, FACTORY, "operator");
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(any(User.class), eq("hr:read"), eq("hr:read_write")))
                .thenReturn(false);

        mockMvc.perform(asUser(get(uri), 10L))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verifyNoInteractions(timeClockService);
    }

    @Test
    @DisplayName("real canonical HR fallback blocks a role with hr=-")
    void realCanonicalRoleWithoutHrReadIsDenied() throws Exception {
        PermissionServiceImpl canonical = realCanonicalPermissionService();
        User actor = user(11L, FACTORY, "operator");
        when(userRepository.findById(11L)).thenReturn(Optional.of(actor));

        mvcFor(canonical).perform(asUser(get(departmentUri()), 11L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeClockService, factoryConfigService, userModuleAccessService);
    }

    @ParameterizedTest(name = "HR reader reaches {0}")
    @MethodSource("aggregateUris")
    @DisplayName("canonical HR reader with enabled hr_employee reaches every aggregate alias")
    void aggregateAllowed(String uri) throws Exception {
        User actor = user(12L, FACTORY, "hr_admin");
        when(userRepository.findById(12L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(any(User.class), eq("hr:read"), eq("hr:read_write")))
                .thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(true);
        stubAggregateService(uri);

        var result = mockMvc.perform(asUser(get(uri), 12L))
                .andExpect(status().isOk())
                .andExpect(uri.contains("/export")
                        ? content().bytes(EXPORT_BYTES)
                        : jsonPath("$.success").value(true));
        result.andReturn();

        verify(factoryConfigService).isModuleEnabled(FACTORY, "hr_employee");
        verify(userModuleAccessService).canAccessModule(actor, "hr_employee");
        verifyAggregateService(uri);
    }

    @ParameterizedTest(name = "factory module disabled blocks {0}")
    @MethodSource("aggregateUris")
    void factoryModuleDisabledBlocksAggregate(String uri) throws Exception {
        User actor = user(13L, FACTORY, "hr_admin");
        when(userRepository.findById(13L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(any(User.class), eq("hr:read"), eq("hr:read_write")))
                .thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(false);

        mockMvc.perform(asUser(get(uri), 13L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("hr_employee")));

        verifyNoInteractions(timeClockService, userModuleAccessService);
    }

    @ParameterizedTest(name = "account module access denied blocks {0}")
    @MethodSource("aggregateUris")
    void accountModuleDeniedBlocksAggregate(String uri) throws Exception {
        User actor = user(14L, FACTORY, "hr_admin");
        when(userRepository.findById(14L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(actor, "hr:read", "hr:read_write")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(false);

        mockMvc.perform(asUser(get(uri), 14L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeClockService);
    }

    @ParameterizedTest(name = "self endpoint remains available: {0}")
    @MethodSource("selfUris")
    @DisplayName("personal timeclock reads keep JWT self access without aggregate HR rights")
    void selfReadDoesNotRequireAggregateHrAccess(String uri) throws Exception {
        mockMvc.perform(asUser(get(uri), 21L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verifyNoInteractions(permissionService, userModuleAccessService, factoryConfigService, userRepository);
    }

    private void stubAggregateService(String uri) {
        if (uri.contains("/department/")) {
            when(timeClockService.getDepartmentAttendance(eq(FACTORY), eq("PROCESSING"), any()))
                    .thenReturn(Map.of("total", 0));
        } else if (uri.endsWith("/export?startDate=" + START + "&endDate=" + END)) {
            when(timeClockService.exportAttendanceRecords(eq(FACTORY), any(), any()))
                    .thenReturn(EXPORT_BYTES);
        } else if (uri.contains("/admin/history")) {
            when(timeClockService.getAllEmployeesClockHistory(eq(FACTORY), any(), any(), any()))
                    .thenReturn(com.cretas.aims.dto.common.PageResponse.of(List.of(), 1, 20, 0L));
        } else if (uri.contains("/admin/statistics")) {
            when(timeClockService.getAllEmployeesAttendanceStatistics(eq(FACTORY), any(), any()))
                    .thenReturn(Map.of("total", 0));
        }
    }

    private void verifyAggregateService(String uri) {
        if (uri.contains("/department/")) {
            verify(timeClockService).getDepartmentAttendance(eq(FACTORY), eq("PROCESSING"), any());
        } else if (uri.endsWith("/export?startDate=" + START + "&endDate=" + END)) {
            verify(timeClockService).exportAttendanceRecords(eq(FACTORY), any(), any());
        } else if (uri.contains("/admin/history")) {
            verify(timeClockService).getAllEmployeesClockHistory(eq(FACTORY), any(), any(), any());
        } else if (uri.contains("/admin/statistics")) {
            verify(timeClockService).getAllEmployeesAttendanceStatistics(eq(FACTORY), any(), any());
        }
    }

    private static PermissionServiceImpl realCanonicalPermissionService() {
        PermissionServiceImpl canonical = new PermissionServiceImpl();
        ReflectionTestUtils.setField(canonical, "platformRepo", mock(PlatformRolePermissionRepository.class));
        ReflectionTestUtils.setField(canonical, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        return canonical;
    }

    private static Stream<String> aggregateUris() {
        return Stream.of(
                departmentUri(),
                "/api/mobile/F006/timeclock/export?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/timeclock/admin/history?startDate=" + START
                        + "&endDate=" + END + "&page=1&size=20",
                "/api/mobile/F006/timeclock/admin/statistics?startDate=" + START + "&endDate=" + END);
    }

    private static Stream<String> selfUris() {
        return Stream.of(
                "/api/mobile/F006/timeclock/status",
                "/api/mobile/F006/timeclock/history?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/timeclock/today",
                "/api/mobile/F006/timeclock/statistics?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/timeclock/monthly?year=2026&month=10&page=1&size=31");
    }

    private static String departmentUri() {
        return "/api/mobile/F006/timeclock/department/PROCESSING?date=2026-10-04";
    }

    private static MockHttpServletRequestBuilder asUser(
            MockHttpServletRequestBuilder builder, long userId) {
        return builder.requestAttr("userId", userId);
    }

    private static User user(long id, String factoryId, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setRoleCode(roleCode);
        user.setIsActive(true);
        return user;
    }
}
