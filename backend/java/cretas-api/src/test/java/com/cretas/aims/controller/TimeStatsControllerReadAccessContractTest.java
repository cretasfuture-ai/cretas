package com.cretas.aims.controller;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TimeStatsService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TimeStatsController 的真实 MVC 读权限契约。
 *
 * <p>聚合统计必须穿过真实 PermissionInterceptor + ModuleEnabledInterceptor；
 * 员工个人统计保持同厂有效员工自读，并把跨人读取收紧为 canonical hr read、
 * 当前账号模块访问和工厂模块启用三者同时满足。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimeStatsController read access (MockMvc + real interceptors)")
class TimeStatsControllerReadAccessContractTest {

    private static final String FACTORY = "F006";
    private static final String START = "2026-10-01";
    private static final String END = "2026-10-04";

    @Mock private TimeStatsService timeStatsService;
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
        TimeStatsController controller = new TimeStatsController(
                timeStatsService,
                canonicalPermissions,
                userModuleAccessService,
                factoryConfigService,
                userRepository);
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

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("aggregateDeniedCases")
    @DisplayName("roles without canonical HR permission cannot read any aggregate alias")
    void aggregateRequiresHrReadAndModule(String role, String uri) throws Exception {
        User actor = user(10L, FACTORY, role);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(any(User.class), eq("hr:read"), eq("hr:read_write")))
                .thenReturn(false);

        mockMvc.perform(asUser(get(uri), 10L))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verifyNoInteractions(timeStatsService);
    }

    @ParameterizedTest(name = "HR reader reaches {0}")
    @MethodSource("aggregateUris")
    @DisplayName("canonical HR reader with enabled hr_employee reaches every aggregate alias")
    void aggregateAllowed(String uri) throws Exception {
        User actor = user(11L, FACTORY, "hr_admin");
        when(userRepository.findById(11L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(any(User.class), eq("hr:read"), eq("hr:read_write")))
                .thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(true);

        mockMvc.perform(asUser(get(uri), 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(factoryConfigService).isModuleEnabled(FACTORY, "hr_employee");
        verify(userModuleAccessService).canAccessModule(actor, "hr_employee");
    }

    @ParameterizedTest
    @MethodSource("aggregateUris")
    @DisplayName("factory module disabled blocks every aggregate before service execution")
    void aggregateModuleDenied(String uri) throws Exception {
        User actor = user(12L, FACTORY, "hr_admin");
        when(userRepository.findById(12L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(any(User.class), eq("hr:read"), eq("hr:read_write")))
                .thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(false);

        mockMvc.perform(asUser(get(uri), 12L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("hr_employee")));

        verifyNoInteractions(timeStatsService);
    }

    @ParameterizedTest
    @MethodSource("aggregateUris")
    void accountModuleDenyBlocksEveryAggregate(String uri) throws Exception {
        User actor = user(12L, FACTORY, "hr_admin");
        when(userRepository.findById(12L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(actor, "hr:read", "hr:read_write")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(false);
        mockMvc.perform(asUser(get(uri), 12L)).andExpect(status().isForbidden());
        verifyNoInteractions(timeStatsService);
    }

    @ParameterizedTest
    @MethodSource("realCanonicalCases")
    void honorsRealCanonicalFallbackInsteadOfInventingRoleDenials(String role, String uri, boolean allowed) throws Exception {
        PermissionServiceImpl canonical = new PermissionServiceImpl();
        ReflectionTestUtils.setField(canonical, "platformRepo", mock(PlatformRolePermissionRepository.class));
        ReflectionTestUtils.setField(canonical, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        User actor = user(71L, FACTORY, role);
        when(userRepository.findById(71L)).thenReturn(Optional.of(actor));
        if (allowed) {
            when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(true);
            when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(true);
        }
        mvcFor(canonical).perform(asUser(get(uri), 71L))
                .andExpect(allowed ? status().isOk() : status().isForbidden());
        if (!allowed) verifyNoInteractions(timeStatsService);
    }

    private static Stream<Arguments> realCanonicalCases() {
        return Stream.of("viewer", "hr_admin", "cashier", "operator")
                .flatMap(role -> aggregateUris().map(uri ->
                        Arguments.of(role, uri, role.equals("viewer") || role.equals("hr_admin"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"operator", "warehouse_worker", "yield_operator", "viewer", "cashier"})
    @DisplayName("same-factory active employee can read own personal statistics without HR module")
    void employeeCanReadSelf(String role) throws Exception {
        User actor = user(21L, FACTORY, role);
        when(userRepository.findByIdAndFactoryId(21L, FACTORY))
                .thenReturn(Optional.of(actor));

        mockMvc.perform(asUser(personalRequest(21), 21L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(timeStatsService).getWorkerTimeStatsById(
                eq(FACTORY), eq(21), any(), any());
        verifyNoInteractions(permissionService, userModuleAccessService, factoryConfigService);
    }

    @Test
    @DisplayName("cross-person read requires canonical HR read, self module access, and factory enablement")
    void hrReaderCanReadAnotherWorker() throws Exception {
        User actor = user(22L, FACTORY, "dispatcher");
        User target = user(23L, FACTORY, "operator");
        when(userRepository.findByIdAndFactoryId(22L, FACTORY)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdAndFactoryId(23L, FACTORY)).thenReturn(Optional.of(target));
        when(permissionService.hasAnyPermission(actor, "hr:read", "hr:read_write")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(true);

        mockMvc.perform(asUser(personalRequest(23), 22L))
                .andExpect(status().isOk());

        verify(timeStatsService).getWorkerTimeStatsById(
                eq(FACTORY), eq(23), any(), any());
    }

    @Test
    @DisplayName("self module GRANT cannot manufacture canonical HR read")
    void moduleGrantAloneCannotReadAnotherWorker() throws Exception {
        User actor = user(24L, FACTORY, "cashier");
        User target = user(25L, FACTORY, "operator");
        when(userRepository.findByIdAndFactoryId(24L, FACTORY)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdAndFactoryId(25L, FACTORY)).thenReturn(Optional.of(target));
        when(permissionService.hasAnyPermission(actor, "hr:read", "hr:read_write")).thenReturn(false);

        mockMvc.perform(asUser(personalRequest(25), 24L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeStatsService);
    }

    @Test
    @DisplayName("module DENY blocks cross-person personal read with HTTP 403")
    void personalModuleDenied() throws Exception {
        User actor = user(26L, FACTORY, "dispatcher");
        User target = user(27L, FACTORY, "operator");
        when(userRepository.findByIdAndFactoryId(26L, FACTORY)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdAndFactoryId(27L, FACTORY)).thenReturn(Optional.of(target));
        when(permissionService.hasAnyPermission(actor, "hr:read", "hr:read_write")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(false);

        mockMvc.perform(asUser(personalRequest(27), 26L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeStatsService);
    }

    @Test
    @DisplayName("factory module disabled blocks cross-person personal read with HTTP 403")
    void personalFactoryModuleDisabled() throws Exception {
        User actor = user(28L, FACTORY, "dispatcher");
        User target = user(29L, FACTORY, "operator");
        when(userRepository.findByIdAndFactoryId(28L, FACTORY)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdAndFactoryId(29L, FACTORY)).thenReturn(Optional.of(target));
        when(permissionService.hasAnyPermission(actor, "hr:read", "hr:read_write")).thenReturn(true);
        when(userModuleAccessService.canAccessModule(actor, "hr_employee")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee")).thenReturn(false);

        mockMvc.perform(asUser(personalRequest(29), 28L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeStatsService);
    }

    @Test
    @DisplayName("missing actor fails closed as 401 without reaching service")
    void missingActorUnauthorized() throws Exception {
        mockMvc.perform(personalRequest(21))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(timeStatsService, userRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void nonPositiveActorUnauthorized(long actorId) throws Exception {
        mockMvc.perform(asUser(personalRequest(21), actorId))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(timeStatsService, userRepository);
    }

    @Test
    @DisplayName("foreign factory actor is rejected before business service")
    void foreignFactoryRejected() throws Exception {
        User actor = user(30L, "F006", "operator");
        when(userRepository.findByIdAndFactoryId(30L, "F007")).thenReturn(Optional.empty());

        mockMvc.perform(asUser(personalRequest("F007", 30), 30L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeStatsService, permissionService,
                userModuleAccessService, factoryConfigService);
    }

    @Test
    @DisplayName("unknown worker ID is rejected without business service")
    void unknownWorkerRejected() throws Exception {
        User actor = user(31L, FACTORY, "operator");
        when(userRepository.findByIdAndFactoryId(31L, FACTORY)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdAndFactoryId(999L, FACTORY)).thenReturn(Optional.empty());

        mockMvc.perform(asUser(personalRequest(999), 31L))
                .andExpect(status().isForbidden());

        verifyNoInteractions(timeStatsService);
    }

    @Test
    @DisplayName("non-numeric worker ID is rejected by MVC before business service")
    void invalidWorkerIdRejected() throws Exception {
        mockMvc.perform(asUser(get("/api/mobile/F006/time-stats/workers/not-a-number")
                        .param("startDate", START).param("endDate", END), 31L))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(timeStatsService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory-disabled", "account-denied", "allowed"})
    void exportHonorsBothModuleGates(String access) throws Exception {
        User actor = user(72L, FACTORY, "hr_admin");
        when(userRepository.findById(72L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(actor, "hr:read_write")).thenReturn(true);
        when(factoryConfigService.isModuleEnabled(FACTORY, "hr_employee"))
                .thenReturn(!access.equals("factory-disabled"));
        if (!access.equals("factory-disabled")) {
            when(userModuleAccessService.canAccessModule(actor, "hr_employee"))
                    .thenReturn(access.equals("allowed"));
        }

        mockMvc.perform(asUser(post("/api/mobile/F006/time-stats/export")
                        .param("startDate", START).param("endDate", END)
                        .param("format", "CSV"), 72L))
                .andExpect(access.equals("allowed") ? status().isOk()
                        : access.equals("factory-disabled") ? status().isBadRequest()
                        : status().isForbidden());

        if (access.equals("allowed")) {
            verify(timeStatsService).exportStatsReport(
                    eq(FACTORY), any(), any(), eq("CSV"));
        } else {
            verifyNoInteractions(timeStatsService);
        }
    }

    @Test
    void readOnlyViewerCannotExportWithRealCanonicalPermission() throws Exception {
        PermissionServiceImpl canonical = new PermissionServiceImpl();
        ReflectionTestUtils.setField(canonical, "platformRepo", mock(PlatformRolePermissionRepository.class));
        ReflectionTestUtils.setField(canonical, "factoryConfigRepo", mock(FactoryModuleConfigRepository.class));
        User actor = user(73L, FACTORY, "viewer");
        when(userRepository.findById(73L)).thenReturn(Optional.of(actor));

        mvcFor(canonical).perform(asUser(post("/api/mobile/F006/time-stats/export")
                        .param("startDate", START).param("endDate", END), 73L))
                .andExpect(status().isForbidden());
        verifyNoInteractions(timeStatsService, factoryConfigService, userModuleAccessService);
    }

    private static Stream<Arguments> aggregateDeniedCases() {
        return aggregateUris().flatMap(uri -> Stream.of(
                Arguments.of("operator", uri),
                Arguments.of("cashier", uri)));
    }

    private static Stream<String> aggregateUris() {
        return Stream.of(
                "/api/mobile/F006/time-stats/daily?date=2026-10-04",
                "/api/mobile/F006/time-stats/daily/range?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/time-stats/weekly?year=2026&week=40",
                "/api/mobile/F006/time-stats/monthly?year=2026&month=10",
                "/api/mobile/F006/time-stats/yearly?year=2026",
                "/api/mobile/F006/time-stats/by-work-type?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/time-stats/by-department?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/time-stats/productivity?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/time-stats/workers?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/time-stats/realtime",
                "/api/mobile/F006/time-stats/comparative?period1Start=" + START
                        + "&period1End=" + END + "&period2Start=2026-09-01&period2End=2026-09-04",
                "/api/mobile/F006/time-stats/anomaly?startDate=" + START + "&endDate=" + END,
                "/api/mobile/F006/time-stats/trend?startDate=" + START + "&endDate=" + END);
    }

    private static MockHttpServletRequestBuilder asUser(
            MockHttpServletRequestBuilder builder, long userId) {
        return builder.requestAttr("userId", userId);
    }

    private static MockHttpServletRequestBuilder personalRequest(int workerId) {
        return personalRequest(FACTORY, workerId);
    }

    private static MockHttpServletRequestBuilder personalRequest(String factoryId, int workerId) {
        return get("/api/mobile/{factoryId}/time-stats/workers/{workerId}", factoryId, workerId)
                .param("startDate", START)
                .param("endDate", END);
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
