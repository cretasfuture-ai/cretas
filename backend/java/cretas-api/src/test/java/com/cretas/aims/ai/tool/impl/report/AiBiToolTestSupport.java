package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI 问答 BI 第 2 段三个工具的契约测试共用: 权限矩阵桩 + 价格可见性桩 + 价格上下文泄漏守卫。 */
final class AiBiToolTestSupport {
    private AiBiToolTestSupport() {}

    /** 价格可见性 ThreadLocal 不得被测试留在线程上; 返回清理前是否已是隐藏态 (true=泄漏)。 */
    static boolean clearPriceContextReportingLeak() {
        boolean leaked = com.cretas.aims.security.PriceSensitiveContext.shouldHide("procurement:price:view");
        com.cretas.aims.security.PriceSensitiveContext.clear();
        return leaked;
    }

    static PriceFieldResponseAdvice priceView(boolean shows) {
        return priceView(shows, shows);
    }

    /** amounts = 金额 / 成本覆盖率这类键看不看得到; marginRate = 毛利率 (价格查看口径 + marginRate 键) 看不看得到。 */
    static PriceFieldResponseAdvice priceView(boolean amounts, boolean marginRate) {
        PriceFieldResponseAdvice v = mock(PriceFieldResponseAdvice.class);
        when(v.showsKey(any(), any())).thenAnswer(call -> "marginRate".equals(call.getArgument(1)) ? marginRate : amounts);
        when(v.showsSpec(any(), any())).thenReturn(marginRate);
        return v;
    }

    /** 持有 heldCodes 的用户经中央 ToolRbacEnforcer 过这个工具是否放行。 */
    static boolean enforcerAllows(ToolExecutor tool, Set<String> heldCodes) {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        User u = new User();
        u.setId(9601L);
        u.setFactoryId("F1");
        u.setRoleCode("production_manager");
        when(users.findById(9601L)).thenReturn(Optional.of(u));
        when(permissions.hasAnyPermission(any(), any(String[].class))).thenAnswer(call -> {
            Set<String> asked = new HashSet<>();
            Object[] args = call.getArguments();
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof String code) asked.add(code);
                if (args[i] instanceof String[] codes) asked.addAll(java.util.Arrays.asList(codes));
            }
            return asked.stream().anyMatch(heldCodes::contains);
        });
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", "F1");
        ctx.put("userId", 9601L);
        ctx.put("userRole", "production_manager");
        return enforcer.check(tool, ctx).isAllowed();
    }
}
