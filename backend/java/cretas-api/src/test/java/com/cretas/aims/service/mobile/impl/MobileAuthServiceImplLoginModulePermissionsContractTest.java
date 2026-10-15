package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.SessionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WhitelistRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import com.cretas.aims.service.TempTokenService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906: 产品真实入口 {@code unifiedLogin}
 * 的响应必须带 {@code modulePermissions}, 且它就是 {@link PermissionService#resolveModuleLevels}
 * 对<b>这个登录用户</b>算出来的那份 —— 不是别的用户、不是硬编码矩阵。
 *
 * <p>形态 B 的接线断言: {@code resolveModuleLevels} 写对了 ≠ 登录响应带上了它。
 * 这里桩掉 PermissionService, 只验「登录路径调了它、把结果放进了响应」。
 * 解析链本身的正确性由 {@code PermissionServiceImplResolveModuleLevelsContractTest} 守。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileAuthServiceImplLoginModulePermissionsContractTest {

    @Test
    void serializedFactoryResponseKeepsExistingShapeWhilePlatformHasExplicitNamespace() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var factory = MobileDTO.LoginResponse.builder().userId(62L).factoryId("F1").role("factory_super_admin").build();
        var platform = MobileDTO.LoginResponse.builder().userId(62L).role("super_admin").actorType("PLATFORM_ADMIN").build();
        assertThat(mapper.readTree(mapper.writeValueAsString(factory)).has("actorType")).isFalse();
        assertThat(mapper.readTree(mapper.writeValueAsString(platform)).get("actorType").asText()).isEqualTo("PLATFORM_ADMIN");
    }

    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private WhitelistRepository whitelistRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private UserMapper userMapper;
    @Mock private TempTokenService tempTokenService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private MobileDeviceService mobileDeviceService;
    @Mock private PermissionService permissionService;

    private MobileAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MobileAuthServiceImpl(
                userRepository, sessionRepository, platformAdminRepository, whitelistRepository,
                passwordEncoder, jwtUtil, userMapper, tempTokenService, tokenBlacklistService,
                mobileDeviceService, permissionService);
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString(), anyString())).thenReturn("fake-jwt");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("fake-refresh");
        when(passwordEncoder.matches(anyString(), any())).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User salesManager() {
        Factory factory = new Factory();
        factory.setId("F006");
        factory.setName("F006");
        factory.setType(FactoryType.FACTORY);
        User u = new User();
        u.setId(6001L);
        u.setFactoryId("F006");
        u.setUsername("f006_sales_mgr");
        u.setPassword("hash");
        u.setRoleCode("sales_manager");
        u.setIsActive(true);
        u.setFactory(factory);
        return u;
    }

    @Test
    @DisplayName("unifiedLogin 响应的 modulePermissions == resolveModuleLevels(登录的那个 user)")
    void unifiedLoginCarriesResolvedModuleLevels() {
        User user = salesManager();
        when(platformAdminRepository.findByUsername("f006_sales_mgr")).thenReturn(Optional.empty());
        when(userRepository.findByFactoryIdAndUsername("F006", "f006_sales_mgr")).thenReturn(Optional.of(user));
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put("sales", "rw");
        resolved.put("warehouse", "-");   // 与 RN 旧矩阵 (r) 不同的那一格 —— 登录响应带的是 L1 解析结果
        when(permissionService.resolveModuleLevels(user)).thenReturn(resolved);

        MobileDTO.LoginRequest req = new MobileDTO.LoginRequest();
        req.setUsername("f006_sales_mgr");
        req.setPassword("123456");
        req.setFactoryId("F006");

        MobileDTO.LoginResponse response = service.unifiedLogin(req);
        System.out.println("OBSERVED login modulePermissions = " + response.getModulePermissions());

        assertThat(response.getModulePermissions()).containsExactlyInAnyOrderEntriesOf(resolved);
        verify(permissionService).resolveModuleLevels(user);
        // 既有契约不受影响: permissions 仍是 "<prefix>:*" 平铺数组 (RN backendPermissionContract 钉着它)
        assertThat(response.getPermissions()).containsExactly("sales:*");
    }

    @Test
    @DisplayName("平台管理员登录: modulePermissions 为 null (RN 对平台管理员短路放行, 不读它), 不调 resolveModuleLevels")
    void platformAdminLoginLeavesModulePermissionsNull() {
        when(jwtUtil.generatePlatformToken(1L, "platform_root", "super_admin")).thenReturn("typed-platform-jwt");
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(1L);
        admin.setUsername("platform_root");
        admin.setPasswordHash("hash");
        admin.setPlatformRole(PlatformRole.super_admin);
        when(platformAdminRepository.findByUsername("platform_root")).thenReturn(Optional.of(admin));
        when(platformAdminRepository.save(any(PlatformAdmin.class))).thenAnswer(inv -> inv.getArgument(0));

        MobileDTO.LoginRequest req = new MobileDTO.LoginRequest();
        req.setUsername("platform_root");
        req.setPassword("x");

        MobileDTO.LoginResponse response = service.unifiedLogin(req);
        System.out.println("OBSERVED platform admin modulePermissions = " + response.getModulePermissions());

        assertThat(response.getModulePermissions()).isNull();
        assertThat(response.getToken()).isEqualTo("typed-platform-jwt");
        assertThat(response.getActorType()).isEqualTo("PLATFORM_ADMIN");
        verify(jwtUtil).generatePlatformToken(1L, "platform_root", "super_admin");
        verify(jwtUtil, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
        verify(permissionService, never()).resolveModuleLevels(any());
    }

    @Test
    void platformRefreshUsesExplicitPlatformIssuerAndLiveRole() {
        PlatformAdmin admin = new PlatformAdmin(); admin.setId(62L);
        admin.setUsername("platform_root"); admin.setPlatformRole(PlatformRole.super_admin);
        when(jwtUtil.validateToken("refresh-platform")).thenReturn(true);
        when(jwtUtil.getUserIdFromTokenAsString("refresh-platform")).thenReturn("platform_62");
        when(platformAdminRepository.findById(62L)).thenReturn(Optional.of(admin));
        when(jwtUtil.generatePlatformToken(62L, "platform_root", "super_admin")).thenReturn("typed-platform-jwt");
        var response = service.refreshToken("refresh-platform");
        assertThat(response.getToken()).isEqualTo("typed-platform-jwt");
        assertThat(response.getActorType()).isEqualTo("PLATFORM_ADMIN");
        verify(jwtUtil, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
        verify(userRepository, never()).findById(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"L1", "L2"})
    void unifiedLoginPropagatesActualPermissionRepositoryUnavailability(String layer) {
        User user = salesManager();
        when(platformAdminRepository.findByUsername(user.getUsername())).thenReturn(Optional.empty());
        when(userRepository.findByFactoryIdAndUsername("F006", user.getUsername())).thenReturn(Optional.of(user));
        PermissionServiceImpl actualPermissions = new PermissionServiceImpl();
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        PermissionRepositoryFixtures.successfulEmpty(l1, l2);
        ReflectionTestUtils.setField(actualPermissions, "platformRepo", l1);
        ReflectionTestUtils.setField(actualPermissions, "factoryConfigRepo", l2);
        if ("L1".equals(layer)) {
            when(l1.findByRoleCodeAndDeletedAtIsNull(anyString())).thenThrow(new RuntimeException("L1 unavailable"));
        } else {
            when(l2.findByFactoryIdAndConfigVersion("F006", 1)).thenThrow(new RuntimeException("L2 unavailable"));
        }
        ReflectionTestUtils.setField(service, "permissionService", actualPermissions);
        MobileDTO.LoginRequest request = new MobileDTO.LoginRequest();
        request.setFactoryId("F006"); request.setUsername(user.getUsername()); request.setPassword("fixture");

        assertThatThrownBy(() -> service.unifiedLogin(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("权限配置暂时无法读取，请稍后重试")
                .extracting("code").isEqualTo(503);
    }

    @ParameterizedTest
    @ValueSource(strings = {"L1", "L2"})
    void ordinaryFactoryAdministratorLoginPreservesIndependentWildcardDuringOutage(String layer) {
        User user = salesManager();
        user.setRoleCode("factory_super_admin");
        when(platformAdminRepository.findByUsername(user.getUsername())).thenReturn(Optional.empty());
        when(userRepository.findByFactoryIdAndUsername("F006", user.getUsername())).thenReturn(Optional.of(user));
        PermissionServiceImpl actualPermissions = new PermissionServiceImpl();
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        PermissionRepositoryFixtures.successfulEmpty(l1, l2);
        ReflectionTestUtils.setField(actualPermissions, "platformRepo", l1);
        ReflectionTestUtils.setField(actualPermissions, "factoryConfigRepo", l2);
        if ("L1".equals(layer)) {
            when(l1.findByRoleCodeAndDeletedAtIsNull(anyString())).thenThrow(new RuntimeException("L1 unavailable"));
        } else {
            when(l2.findByFactoryIdAndConfigVersion("F006", 1)).thenThrow(new RuntimeException("L2 unavailable"));
        }
        ReflectionTestUtils.setField(service, "permissionService", actualPermissions);
        MobileDTO.LoginRequest request = new MobileDTO.LoginRequest();
        request.setFactoryId("F006"); request.setUsername(user.getUsername()); request.setPassword("fixture");
        assertThat(service.unifiedLogin(request).getModulePermissions()).containsEntry("*", "rw");
    }
}
