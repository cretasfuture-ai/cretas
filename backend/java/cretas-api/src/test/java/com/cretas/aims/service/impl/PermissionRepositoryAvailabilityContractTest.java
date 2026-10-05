package com.cretas.aims.service.impl;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.entity.config.FactoryModuleConfig;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Availability is an authorization state: repository outage must not become a grant. */
class PermissionRepositoryAvailabilityContractTest {
    private final PlatformRolePermissionRepository platformRepo = mock(PlatformRolePermissionRepository.class);
    private final FactoryModuleConfigRepository factoryRepo = mock(FactoryModuleConfigRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionServiceImpl service = new PermissionServiceImpl();
    private static final AtomicInteger CONTROLLER_EXECUTIONS = new AtomicInteger();
    private User actor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "platformRepo", platformRepo);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", factoryRepo);
        PermissionRepositoryFixtures.successfulEmpty(platformRepo, factoryRepo);
        actor = new User();
        actor.setId(56001L);
        actor.setFactoryId("F006");
        actor.setRoleCode(FactoryUserRole.sales_manager.name());
        actor.setIsActive(true);
        CONTROLLER_EXECUTIONS.set(0);
        when(users.findById(actor.getId())).thenReturn(Optional.of(actor));
    }

    @Test
    void freshHasPermissionReturns503AndHttpDoesNotExecuteControllerWhenL1Throws() throws Exception {
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString()))
                .thenThrow(new RuntimeException("database unavailable"));

        MockMvc mvc = mvc();
        mvc.perform(get("/fixture/permission").requestAttr("userId", actor.getId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("权限配置暂时无法读取，请稍后重试"));
        assertThat(CONTROLLER_EXECUTIONS).hasValue(0);
    }

    @Test
    void recoveryRetriesAuthoritativeDenyWithoutInvalidate() {
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString()))
                .thenThrow(new RuntimeException("database unavailable"))
                .thenReturn(Optional.of(denyRow()));

        assertThatThrownBy(() -> service.hasPermission(actor, "sales:read"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(503);
        assertThat(service.hasPermission(actor, "sales:read")).isFalse();
        verify(platformRepo, times(2)).findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    void l2OutageIs503AndRecoveryDeniesWithoutFallingThroughToL1() throws Exception {
        when(factoryRepo.findByFactoryIdAndConfigVersion("F006", 1))
                .thenThrow(new RuntimeException("L2 database unavailable"))
                .thenReturn(List.of(override("F006", "none")));
        MockMvc mvc = mvc();
        mvc.perform(get("/fixture/permission").requestAttr("userId", actor.getId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("权限配置暂时无法读取，请稍后重试"));
        mvc.perform(get("/fixture/permission").requestAttr("userId", actor.getId()))
                .andExpect(status().isForbidden());
        assertThat(CONTROLLER_EXECUTIONS).hasValue(0);
        verify(factoryRepo, times(2)).findByFactoryIdAndConfigVersion("F006", 1);
        verify(platformRepo, never()).findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    void successfulEmptyRepositoryResultsStillAllowTheProtectedEndpoint() throws Exception {
        mvc().perform(get("/fixture/permission").requestAttr("userId", actor.getId()))
                .andExpect(status().isOk());
        assertThat(CONTROLLER_EXECUTIONS).hasValue(1);
        verify(factoryRepo).findByFactoryIdAndConfigVersion("F006", 1);
        verify(platformRepo).findByRoleCodeAndModuleCodeAndDeletedAtIsNull("sales_manager", "sales");
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthyCacheHonorsTtlExpiryAndExplicitInvalidation() {
        assertThat(service.hasPermission(actor, "sales:read")).isTrue();
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.of(denyRow()));
        assertThat(service.hasPermission(actor, "sales:read")).isTrue();
        verify(platformRepo, times(1)).findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString());
        Map<String, long[]> expiry = (Map<String, long[]>) ReflectionTestUtils.getField(service, "resolveCache");
        assertThat(expiry).hasSize(1);
        long[] deadline = expiry.values().iterator().next();
        assertThat(deadline[0] - System.currentTimeMillis()).isBetween(290_000L, 300_000L);
        deadline[0] = System.currentTimeMillis() - 1; // expire the actual entry without a five-minute sleep
        assertThat(service.hasPermission(actor, "sales:read")).isFalse();
        PlatformRolePermission allow = denyRow();
        allow.setPermissionLevel("read_write");
        when(platformRepo.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.of(allow));
        assertThat(service.hasPermission(actor, "sales:read")).isFalse();
        service.invalidateCache();
        assertThat(service.hasPermission(actor, "sales:read")).isTrue();
        verify(platformRepo, times(3)).findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString());
    }

    @Test
    void sameActorIdInAnotherFactoryDoesNotReuseTheFirstFactoryCache() {
        when(factoryRepo.findByFactoryIdAndConfigVersion("F006", 1))
                .thenReturn(List.of(override("F006", "none")));
        when(factoryRepo.findByFactoryIdAndConfigVersion("F007", 1))
                .thenReturn(List.of(override("F007", "read_write")));
        assertThat(service.hasPermission(actor, "sales:read")).isFalse();
        actor.setFactoryId("F007");
        assertThat(service.hasPermission(actor, "sales:read")).isTrue();
        actor.setFactoryId("F006");
        assertThat(service.hasPermission(actor, "sales:read")).isFalse();
        verify(factoryRepo, times(1)).findByFactoryIdAndConfigVersion("F006", 1);
        verify(factoryRepo, times(1)).findByFactoryIdAndConfigVersion("F007", 1);
    }

    private FactoryModuleConfig override(String factory, String level) {
        FactoryModuleConfig config = new FactoryModuleConfig();
        config.setFactoryId(factory);
        config.setRoleModuleOverride(Map.of("sales_manager", Map.of("sales", level)));
        return config;
    }

    private PlatformRolePermission denyRow() {
        PlatformRolePermission row = new PlatformRolePermission();
        row.setRoleCode(FactoryUserRole.sales_manager.name());
        row.setModuleCode("sales");
        row.setPermissionLevel("none");
        return row;
    }

    @Test
    void bulkResolutionPropagatesAvailabilityAndSuccessfulEmptyStillUsesFallback() {
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString()))
                .thenThrow(new RuntimeException("database unavailable"));
        assertThatThrownBy(() -> service.resolveRoleModuleLevels("F006", FactoryUserRole.sales_manager))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(503);

        reset(platformRepo);
        PermissionRepositoryFixtures.successfulEmpty(platformRepo, factoryRepo);
        assertThat(service.resolveRoleModuleLevels("F006", FactoryUserRole.sales_manager))
                .containsEntry("sales", "rw");
    }

    @Test
    void independentShortCircuitsRemainAvailableDuringRepositoryOutage() {
        ReflectionTestUtils.setField(service, "platformRepo", null);
        ReflectionTestUtils.setField(service, "factoryConfigRepo", null);
        assertThat(service.hasPermission(actor, "procurement:price:view")).isTrue();
        assertThat(service.hasPermission(actor, "procurement:finance_review:view")).isFalse();
        assertThat(service.hasPermission(actor, "hr:self_wage:view")).isTrue();
        assertThat(service.hasPermission(actor, PermissionServiceImpl.YIELD_STANDARD_WRITE_PERMISSION)).isFalse();
        User admin = new User();
        admin.setRoleCode(FactoryUserRole.factory_super_admin.name());
        assertThat(service.hasPermission(admin, "sales:read")).isTrue();
    }

    @Test
    void missingRepositoryDependencyIs503InsteadOfHardcodedGrant() {
        ReflectionTestUtils.setField(service, "factoryConfigRepo", null);
        assertThatThrownBy(() -> service.hasPermission(actor, "sales:read"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(503);

        ReflectionTestUtils.setField(service, "factoryConfigRepo", factoryRepo);
        ReflectionTestUtils.setField(service, "platformRepo", null);
        assertThatThrownBy(() -> service.hasPermission(actor, "sales:read"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(503);
    }

    @Test
    void ordinaryAdministratorSnapshotKeepsWildcardWhileStrictAuthorizationRequiresAvailability() {
        actor.setRoleCode(FactoryUserRole.factory_super_admin.name());
        when(factoryRepo.findByFactoryIdAndConfigVersion("F006", 1))
                .thenThrow(new RuntimeException("L2 unavailable"));
        when(platformRepo.findByRoleCodeAndDeletedAtIsNull(anyString()))
                .thenThrow(new RuntimeException("L1 unavailable"));
        assertThat(service.resolveModuleLevels(actor)).containsEntry("*", "rw");
        assertThat(service.resolveRoleModuleLevels("F006", FactoryUserRole.factory_super_admin))
                .containsEntry("*", "rw");
        assertThatThrownBy(() -> service.resolveModuleLevelsForAuthorization(actor))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller())
                .addInterceptors(new PermissionInterceptor(service, users, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private FixtureController controller() {
        return new FixtureController();
    }

    @RestController
    static class FixtureController {
        @GetMapping("/fixture/permission")
        @RequirePermission("sales:read")
        String protectedEndpoint() {
            CONTROLLER_EXECUTIONS.incrementAndGet();
            return "executed";
        }
    }
}
