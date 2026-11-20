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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AI 权限拒答文案里的功能名 / 权限名要解析成中文 (线上 83ab4dba35 题单 Q07/Q17 实测:
 * 「没有权限使用「该功能」… 需要的权限: 对应模块的操作权限」)。
 * 走产品真实入口 ToolRbacEnforcer.check (Q07/Q17 的 COST_QUERY → report_cost_query, 声明 analytics:read_write)。
 * 只断言文案; 放行/拒绝的判定本身不变 (权限由 mock 的 PermissionService 决定)。
 */
@DisplayName("拒答文案: 功能名取意图目录、权限名取权限模块目录")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolRbacDenyLabelResolveContractTest {

    private static final long UID = 9101L;

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
        User u = new User();
        u.setId(UID);
        u.setFactoryId("F006");
        u.setRoleCode("sales_manager");
        when(userRepository.findById(UID)).thenReturn(java.util.Optional.of(u));
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", "F006");
        c.put("userId", UID);
        c.put("userRole", "sales_manager");
        return c;
    }

    private static ToolExecutor tool(String name, Set<String> perms) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> x) { return "{}"; }
            @Override public Set<String> getRequiredPermissions() { return perms; }
        };
    }

    @Test
    @DisplayName("Q07/Q17 路径 (report_cost_query + analytics:read_write): 文案含具体功能名与权限名, 不含「该功能」「对应模块的」")
    void q07q17CostQueryDenialNamesFeatureAndPermission() {
        ToolRbacEnforcer.Decision d = enforcer.check(tool("report_cost_query", Set.of("analytics:read_write")), ctx());
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getMessage())
                .contains("「成本查询」")
                .contains("数据分析（操作）")
                .doesNotContain("该功能").doesNotContain("对应模块的")
                .doesNotContain("report_cost_query").doesNotContain("analytics");
    }

    @Test
    @DisplayName("毛利路径: 工具名 → 意图名, 读/写权限分别说清, 写权限不说成查看")
    void grossMarginDenialNamesBothLevels() {
        String msg = enforcer.check(tool("report_sku_gross_margin", Set.of("finance:read", "finance:read_write")), ctx()).getMessage();
        assertThat(msg).contains("「SKU毛利率查询」").contains("财务管理（查看）").contains("财务管理（操作）")
                .doesNotContain("该功能");
    }

    @Test
    @DisplayName("澄清候选被滤空走的静态入口: 传意图中文名 / 意图码都解析, 权限同源")
    void standardDenyMessageResolvesIntentCodeAndName() {
        assertThat(ToolRbacGuard.standardDenyMessage("viewer", "COST_QUERY", "analytics:read_write"))
                .contains("「成本查询」").contains("数据分析（操作）");
        assertThat(ToolRbacGuard.standardDenyMessage("viewer", "销售订单查询", "sales:read"))
                .contains("「销售订单查询」").contains("销售管理（查看）");
    }

    @Test
    @DisplayName("兜底: 目录里没有的工具名 / 模块码不露 ASCII 标识, 仍说「该功能」「对应模块的…」")
    void unregisteredToolAndModuleFallBack() {
        String msg = enforcer.check(tool("totally_unregistered_tool_xyz", Set.of("no_such_module:read_write")), ctx()).getMessage();
        assertThat(msg).contains("「该功能」").contains("对应模块的操作权限")
                .doesNotContain("totally_unregistered_tool_xyz").doesNotContain("no_such_module");
    }
}
