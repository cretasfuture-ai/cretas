package com.cretas.aims.config;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 在线会话闸: 工厂关停后, 已签发的 access token (24h 有效) 也要被挡住,
 * 否则 App 里已登录的人关厂后还能继续用一整天.
 *
 * <p>返回 401 而不是 403 是有意的: RN / web-admin 对 401 的处理是「尝试 refresh →
 * refresh 被服务层 403 拒 → 清本地凭证 → 回登录页」, 用户再登录时看到「当前服务已关闭」.
 * 403 只会在当前屏弹一条错误, 会话仍挂着.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JwtAuthInterceptor — 工厂关停后在线会话被挡")
class JwtAuthInterceptorFactoryClosedTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private FactoryRepository factoryRepository;
    @Mock private UserRepository userRepository;

    private JwtAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", tokenBlacklistService);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", factoryRepository);
        ReflectionTestUtils.setField(interceptor, "userRepository", userRepository);

        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);
        when(jwtUtil.getUserIdFromToken(anyString())).thenReturn(77L);
        when(jwtUtil.getUsernameFromToken(anyString())).thenReturn("op1");
        when(userRepository.findById(77L)).thenAnswer(invocation -> {
            User user = new User();
            user.setId(77L);
            user.setFactoryId(jwtUtil.getFactoryIdFromToken("some-token"));
            user.setIsActive(true);
            return Optional.of(user);
        });
    }

    private void tokenOf(String factoryId, String role) {
        when(jwtUtil.getFactoryIdFromToken(anyString())).thenReturn(factoryId);
        when(jwtUtil.getRoleFromToken(anyString())).thenReturn(role);
    }

    private void factoryIs(String id, boolean active) {
        Factory f = new Factory();
        f.setId(id);
        f.setIsActive(active);
        when(factoryRepository.findById(id)).thenReturn(Optional.of(f));
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.addHeader("Authorization", "Bearer some-token");
        return req;
    }

    @Test
    @DisplayName("工厂已关停: 合法 token 访问本工厂接口 → 401 / 当前服务已关闭")
    void closedFactory_tokenRejected() throws Exception {
        tokenOf("LIUSHANMEN", "operator");
        factoryIs("LIUSHANMEN", false);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("/api/mobile/LIUSHANMEN/dashboard/overview"), resp, null);

        assertThat(proceed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("\"message\":\"当前服务已关闭\"");
    }

    @Test
    @DisplayName("阳性对照: 工厂 active 时同一 token 正常放行")
    void activeFactory_tokenAccepted() throws Exception {
        tokenOf("LIUSHANMEN", "operator");
        factoryIs("LIUSHANMEN", true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("/api/mobile/LIUSHANMEN/dashboard/overview"), resp, null);

        assertThat(proceed).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("平台管理员不经过这道闸 —— 关厂是他们操作的, 不能把自己锁在外面")
    void platformAdmin_notChecked() throws Exception {
        tokenOf("PLATFORM", "platform_admin");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("/api/mobile/LIUSHANMEN/dashboard/overview"), resp, null);

        assertThat(proceed).isTrue();
        verify(factoryRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("公开端点 (登录/刷新) 不走这道闸 —— 它们由服务层的同一句话拒绝")
    void publicEndpoint_notChecked() throws Exception {
        tokenOf("LIUSHANMEN", "operator");
        factoryIs("LIUSHANMEN", false);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request("/api/mobile/auth/unified-login"), resp, null);

        assertThat(proceed).isTrue();
        verify(factoryRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("工厂状态有 60s 缓存: 连续两次请求只查一次库")
    void factoryStatusIsCached() throws Exception {
        tokenOf("LIUSHANMEN", "operator");
        factoryIs("LIUSHANMEN", true);

        interceptor.preHandle(request("/api/mobile/LIUSHANMEN/a"), new MockHttpServletResponse(), null);
        interceptor.preHandle(request("/api/mobile/LIUSHANMEN/b"), new MockHttpServletResponse(), null);

        verify(factoryRepository, times(1)).findById("LIUSHANMEN");
    }

    @Test
    @DisplayName("工厂行不存在时不挡 —— 这道闸只认「明确关停」, 不把数据缺口翻译成关停")
    void unknownFactory_notBlocked() throws Exception {
        tokenOf("GHOST", "operator");
        when(factoryRepository.findById("GHOST")).thenReturn(Optional.empty());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/api/mobile/GHOST/a"), resp, null)).isTrue();
    }
}
