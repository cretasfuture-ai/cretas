package com.cretas.aims.config;

import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 刀 D 之后 {@code GET /api/mobile/{factoryId}/roles/{role}/permissions} 的 {@code factoryId}
 * <b>开始真正参与选 L2 {@code factory_module_configs} 的行</b>（改之前它只是路由装饰，
 * 响应是角色枚举的纯函数）。⇒ 「换个 factoryId 能不能读到别人家的 L2」变成一个真问题。
 *
 * <h3>为什么这道校验不写在 controller 里</h3>
 * 因为它<b>已经存在</b>，在 {@link JwtAuthInterceptor}（{@code WebMvcConfig} order=1，
 * 挂在 {@code /api/mobile/**} 上，早于 {@code PermissionInterceptor} 的 order=3）：
 * {@code extractFactoryIdFromUrl} 从 URL 取出 factoryId（{@code roles} <b>不在</b>那串排除
 * 名单里），{@code validateFactoryAccess} 比对 token 的 factoryId，平台角色例外。
 * 在 controller 里再写一遍 = 同一条租户规则有两份（形态 D），而且第二份很容易漏掉平台角色
 * 例外 —— 仓里那个零调用点的 {@code PermissionService.canAccessFactory} 就没有这个例外。
 *
 * <p>⚠️ 误导来源已一并订正：本端点旧 javadoc 写着「工厂ID 路径占位，未做工厂隔离校验」。
 * 那句话描述的是 controller 自己，读的人（包括终审）会读成「这条请求路径上没有租户校验」。
 * 形态 B⁷：<b>从一句注释推出行为，要先跑一次让它自己说</b> —— 所以有了这个测试。
 *
 * <h3>已知盲区（写明，不靠加启发式逼近）</h3>
 * 本测试驱动的是拦截器本身，<b>不证明它被注册在这条路径上</b>。
 * 把 {@code WebMvcConfig:62} 的 {@code addPathPatterns("/api/mobile/**")} 删掉，
 * 这里不会红。那一层由 Spring 的注册代码负责，本仓没有对应的闸。
 */
@DisplayName("角色权限端点 — 跨工厂读必须被拒")
class RolePermissionsEndpointTenantIsolationContractTest {

    private static final String URI_F006 = "/api/mobile/F006/roles/quality_controller/permissions";
    private static final String URI_F001 = "/api/mobile/F001/roles/quality_controller/permissions";

    /** 造一个拦截器，token 声明 (factoryId, role)。 */
    private JwtAuthInterceptor interceptorFor(String tokenFactoryId, String tokenRole) {
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.validateToken(anyString())).thenReturn(true);
        when(jwt.getUserIdFromToken(anyString())).thenReturn(42L);
        when(jwt.getUsernameFromToken(anyString())).thenReturn("tester");
        when(jwt.getFactoryIdFromToken(anyString())).thenReturn(tokenFactoryId);
        when(jwt.getRoleFromToken(anyString())).thenReturn(tokenRole);

        TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
        when(blacklist.isBlacklisted(anyString())).thenReturn(false);

        // 2026-09-15 (#812): 拦截器多了工厂关停闸, 会按 token 的 factoryId 查一次库。
        // 这里的工厂都视为「存在且未关停」—— 本测试守的是跨租户隔离, 不是关停。
        FactoryRepository factories = mock(FactoryRepository.class);
        when(factories.findById(anyString())).thenReturn(Optional.empty());

        JwtAuthInterceptor interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", blacklist);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", factories);
        return interceptor;
    }

    private MockHttpServletRequest requestTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.addHeader("Authorization", "Bearer any-token-the-mock-accepts");
        return request;
    }

    private record Outcome(boolean proceeded, int status) {}

    private Outcome run(JwtAuthInterceptor interceptor, String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        // handler 传 null: 本拦截器不看 handler (它只在 PermissionInterceptor 里才要 HandlerMethod)
        boolean proceeded = interceptor.preHandle(requestTo(uri), response, null);
        return new Outcome(proceeded, response.getStatus());
    }

    @Test
    @DisplayName("阴性对照: F001 的账号请求 F006 的路径 ⇒ 403, 请求不往下走")
    void crossFactoryReadIsRefused() throws Exception {
        Outcome outcome = run(interceptorFor("F001", "factory_super_admin"), URI_F006);

        assertThat(outcome.proceeded())
                .as("🔴 跨工厂请求被放行了 —— 刀 D 之后 factoryId 会去选 L2 行, "
                        + "这等于任意已认证用户可读任意工厂的 role×module 覆盖")
                .isFalse();
        assertThat(outcome.status()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("阳性对照: 同一个账号请求自己工厂的路径 ⇒ 放行 (证明上面那条不是「一律拒绝」)")
    void sameFactoryReadProceeds() throws Exception {
        Outcome outcome = run(interceptorFor("F001", "factory_super_admin"), URI_F001);

        assertThat(outcome.proceeded())
                .as("INSTRUMENT DEAD — 连自己工厂都进不去, 那上面那条 403 证明不了任何事")
                .isTrue();
        assertThat(outcome.status()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("平台角色例外仍在: platform_admin 跨工厂读放行")
    void platformAdminMayReadAnyFactory() throws Exception {
        assertThat(run(interceptorFor("PLATFORM", "platform_admin"), URI_F006).proceeded()).isTrue();
    }

    @Test
    @DisplayName("匿名请求这条路径 ⇒ 401 (不是 403 —— 两者出处不同, 别混)")
    void anonymousIsUnauthorized() throws Exception {
        JwtAuthInterceptor interceptor = interceptorFor("F006", "factory_super_admin");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URI_F006);
        request.setRequestURI(URI_F006);   // 不带 Authorization
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("判据自身: roles 路径确实被当成工厂作用域 (不在排除名单里)")
    void rolesPathIsTreatedAsFactoryScoped() throws Exception {
        // 反面: /api/mobile/system/** 在排除名单里 ⇒ 同一个跨工厂 token 不会被这道闸拦。
        // 它证明上面那条 403 是「roles 被识别成工厂作用域」的结果, 而不是「什么都拦」。
        Outcome excluded = run(interceptorFor("F001", "factory_super_admin"),
                "/api/mobile/system/release-notes");
        assertThat(excluded.proceeded())
                .as("排除名单里的路径不该被工厂校验拦 —— 若这里也 false, 说明上面那条 403 "
                        + "可能来自别的分支, 归因就不成立")
                .isTrue();
    }
}
