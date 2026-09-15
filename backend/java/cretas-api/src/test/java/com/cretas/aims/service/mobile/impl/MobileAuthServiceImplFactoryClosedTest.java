package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.SessionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WhitelistRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TempTokenService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工厂关停 (factories.is_active = false) 时, 该工厂任何账号登录 / 刷新令牌都必须被拒,
 * 且 message 精确等于「当前服务已关闭」—— RN 与 web-admin 登录页都原样显示后端 message,
 * 这句话就是用户看到的全部.
 *
 * <p>2026-09-15 背景: 平台侧早有 deactivateFactory 把 is_active 置 false, 但登录链路
 * 只查 user.isActive, 从不看工厂 —— 停用工厂对登录零效果. 本类钉住修复后的行为,
 * 并配阳性对照 (工厂 active 时同一账号照常登录), 防止这道检查变成恒真式.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileAuthServiceImplFactoryClosedTest {

    static final String CLOSED_MESSAGE = "当前服务已关闭";

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
        ReflectionTestUtils.setField(service, "demoEnabled", true);
        ReflectionTestUtils.setField(service, "demoFactoryFactoryId", "LIUSHANMEN");
        ReflectionTestUtils.setField(service, "demoFactoryUsername", "op1");

        when(platformAdminRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString(), anyString())).thenReturn("jwt");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("refresh");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtUtil.validateToken("refresh")).thenReturn(true);
        when(jwtUtil.getUserIdFromTokenAsString("refresh")).thenReturn("77");
    }

    private User userOfFactory(boolean factoryActive) {
        Factory factory = new Factory();
        factory.setId("LIUSHANMEN");
        factory.setName("六膳门");
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(factoryActive);

        User user = new User();
        user.setId(77L);
        user.setFactoryId("LIUSHANMEN");
        user.setUsername("op1");
        user.setPasswordHash("hashed");
        user.setRoleCode("operator");
        user.setIsActive(true);
        user.setFactory(factory);
        return user;
    }

    private MobileDTO.LoginRequest loginRequest() {
        MobileDTO.LoginRequest req = new MobileDTO.LoginRequest();
        req.setUsername("op1");
        req.setPassword("123456");
        req.setFactoryId("LIUSHANMEN");
        return req;
    }

    @Test
    @DisplayName("工厂已关停: 密码正确、账号启用, 登录仍被拒 403 / 当前服务已关闭")
    void unifiedLogin_factoryClosed_rejected() {
        User user = userOfFactory(false);
        when(userRepository.findByFactoryIdAndUsername("LIUSHANMEN", "op1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.unifiedLogin(loginRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo(CLOSED_MESSAGE);
                });
        // 没签发过令牌, 也没更新 lastLogin
        verify(jwtUtil, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("阳性对照: 工厂 active 时同一账号照常登录")
    void unifiedLogin_factoryActive_succeeds() {
        User user = userOfFactory(true);
        when(userRepository.findByFactoryIdAndUsername("LIUSHANMEN", "op1")).thenReturn(Optional.of(user));

        MobileDTO.LoginResponse resp = service.unifiedLogin(loginRequest());

        assertThat(resp.getFactoryId()).isEqualTo("LIUSHANMEN");
        assertThat(resp.getToken()).isEqualTo("jwt");
    }

    @Test
    @DisplayName("工厂已关停: 刷新令牌同样被拒 —— 否则 30 天 refresh 窗口内旧会话能续命")
    void refreshToken_factoryClosed_rejected() {
        when(userRepository.findById(77L)).thenReturn(Optional.of(userOfFactory(false)));

        assertThatThrownBy(() -> service.refreshToken("refresh"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo(CLOSED_MESSAGE);
                });
        verify(jwtUtil, never()).generateToken(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("阳性对照: 工厂 active 时刷新令牌正常")
    void refreshToken_factoryActive_succeeds() {
        when(userRepository.findById(77L)).thenReturn(Optional.of(userOfFactory(true)));

        assertThat(service.refreshToken("refresh").getToken()).isEqualTo("jwt");
    }

    @Test
    @DisplayName("工厂已关停: demo 免密登录也走同一道闸")
    void demoLogin_factoryClosed_rejected() {
        when(userRepository.findByFactoryIdAndUsername("LIUSHANMEN", "op1"))
                .thenReturn(Optional.of(userOfFactory(false)));

        assertThatThrownBy(() -> service.demoLogin("factory"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(CLOSED_MESSAGE);
    }
}
