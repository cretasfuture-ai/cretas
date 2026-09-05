package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * owner 裁定 (2026-09-06): 「客户名单只有<b>销售、财务、工厂管理员、运营</b>能看。」
 *
 * <p>🔴 这条裁定有<b>两个</b>执行点，不是一个:</p>
 * <ol>
 *   <li>HTTP 面 —— {@code CustomerController#getCustomerList} 上的 {@code @RequirePermission}
 *       (见 {@code CustomerControllerGetCustomerListPermissionTest});</li>
 *   <li><b>AI 面</b> —— {@code CustomerListTool} 直接调 {@code CustomerService#getCustomerList}，
 *       <b>⛔ 完全不经过那个 controller</b>。它继承 {@code AbstractTool} 的默认实现
 *       ({@code requiresPermission()=false} / {@code hasPermission()=true})，
 *       所以在把 {@code customer_list} 加进 {@link ToolRbacEnforcer#TOOL_PERMISSION_MAP} 之前，
 *       生产经理在 HTTP 面拿 403、<b>转头问一句 AI 就能拿到全厂客户名单</b>。</li>
 * </ol>
 *
 * <p>本测试守的就是第 2 个执行点。⚠️ {@code customer_list} 是
 * {@code TOOL_PERMISSION_MAP} 里<b>唯一的只读工具</b> —— 该表原本只收「敏感写工具」，
 * 所以它很容易在后续清理中被当成「放错了」删掉。删之前请读 owner 裁定:
 * 客户名单本身就是受控数据，与读写无关。</p>
 *
 * @see ToolRbacEnforcerTest 中央 enforcer 的通用行为 (低权拒绝 / 只读不受影响 / fail-closed)
 */
@DisplayName("owner 裁定的 AI 执行点: customer_list 也要过 sales|finance 读权限")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerListToolAiPathPermissionTest {

    private static final String FACTORY = "F006";
    /** production_manager: L1 prod 实测 sales='-' finance='-' —— 裁定四类之外。 */
    private static final long PRODUCTION_MANAGER_UID = 9401L;
    /** sales_manager: L1 prod 实测 sales='rw'。 */
    private static final long SALES_MANAGER_UID = 9402L;

    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;

    private ToolRbacEnforcer enforcer;

    @BeforeEach
    void setUp() {
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", userRepository);
        ReflectionTestUtils.setField(guard, "permissionService", permissionService);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        when(userRepository.findById(PRODUCTION_MANAGER_UID))
                .thenReturn(Optional.of(user(PRODUCTION_MANAGER_UID, "production_manager")));
        when(userRepository.findById(SALES_MANAGER_UID))
                .thenReturn(Optional.of(user(SALES_MANAGER_UID, "sales_manager")));
    }

    private static User user(long id, String roleCode) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(FACTORY);
        u.setRoleCode(roleCode);
        return u;
    }

    private static Map<String, Object> ctx(long userId, String role) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", userId);
        c.put("userRole", role);
        return c;
    }

    /** 最小桩工具: 只提供名字, 不自声明权限 —— 与真实 CustomerListTool 一致 (它没 override)。 */
    private static ToolExecutor tool(String name) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public Set<String> getRequiredPermissions() { return Set.of(); }
        };
    }

    @Test
    @DisplayName("🔴 裁定后果 (AI 面): production_manager 问 AI 要客户名单 → 拒绝")
    void productionManagerDeniedOnAiPath() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);

        ToolRbacEnforcer.Decision d =
                enforcer.check(tool("customer_list"), ctx(PRODUCTION_MANAGER_UID, "production_manager"));

        assertThat(d.isAllowed())
                .as("customer_list 必须在 TOOL_PERMISSION_MAP 里 —— 否则 controller 的门是摆设, "
                        + "AI 路径直接绕过它拿到全厂客户名单")
                .isFalse();
    }

    @Test
    @DisplayName("销售: 持 sales:read 的角色经 AI 查客户名单 → 放行")
    void salesManagerAllowedOnAiPath() {
        // 只在被问到「sales:read(_write)」时才给 true —— 避免 any() 恒真让断言失去意义。
        // ⚠️ Mockito varargs 坑 (沿用本仓既有先例): getArguments() 把 varargs 按位置摊平,
        //    index 0 是 user, index 1.. 才是各个权限码 —— ⛔ 不能把 args[1] 当 String[] 强转。
        when(permissionService.hasAnyPermission(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        String code = String.valueOf(args[i]);
                        if ("sales:read".equals(code) || "sales:read_write".equals(code)) {
                            return true;
                        }
                    }
                    return false;
                });

        ToolRbacEnforcer.Decision d =
                enforcer.check(tool("customer_list"), ctx(SALES_MANAGER_UID, "sales_manager"));

        assertThat(d.isAllowed()).isTrue();
    }

    /**
     * 阴性对照 —— 上面两条都经过 mock 出来的 {@code PermissionService}，
     * 所以它们证明的是「enforcer 按映射表里的码放行/拒绝」；
     * <b>映射表里到底写了哪几个码</b>由这条钉住，且必须与 HTTP 面逐字相同 (形态 D: 同一个
     * 裁定有两份执行点, 不钉一致性它一定会漂)。
     */
    @Test
    @DisplayName("两个执行点的权限码必须逐字相同 (HTTP 面 @RequirePermission vs AI 面映射表)")
    void aiPathCodesMatchTheHttpGateVerbatim() {
        assertThat(ToolRbacEnforcer.TOOL_PERMISSION_MAP.get("customer_list"))
                .as("customer_list 的权限码必须与 CustomerController#getCustomerList 的 "
                        + "@RequirePermission 完全一致 —— 同一条 owner 裁定的两个执行点")
                .isEqualTo(Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read"));
    }
}
