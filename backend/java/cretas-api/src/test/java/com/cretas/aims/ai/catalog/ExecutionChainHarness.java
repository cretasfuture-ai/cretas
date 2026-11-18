package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.execution.TrustedExecutionContext;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用: 生产同一条 {@link ToolPermissionChain} (一线门 / 旧角色串 / ToolRbacEnforcer), 岗位矩阵取
 * {@link PermissionServiceImpl} 内置默认 (L1/L2 表为空)。按岗位造用户, 问「这个岗位持有某码吗 / 执行时过得了工具门吗」。
 */
final class ExecutionChainHarness {

    private static final String FACTORY = "F006";

    private final Map<String, ToolExecutor> tools;
    private final PermissionServiceImpl matrix;
    private final UserRepository users;
    private final ToolPermissionChain chain;
    private final Map<String, User> byRole = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(98_000L);

    private ExecutionChainHarness(Map<String, ToolExecutor> tools) {
        this.tools = tools;
        this.matrix = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(matrix);
        this.users = mock(UserRepository.class);
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", matrix);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        this.chain = new ToolPermissionChain(enforcer, new FrontlineAiWriteGrantService(
                mock(com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository.class),
                new WriteGuardService(),
                mock(com.cretas.aims.service.intent.IntentConfigManagementService.class),
                mock(ToolRegistry.class)));
    }

    static ExecutionChainHarness build(Map<String, ToolExecutor> tools) {
        return new ExecutionChainHarness(tools);
    }

    private User user(String role) {
        return byRole.computeIfAbsent(role, r -> {
            long id = ids.incrementAndGet();
            User u = new User();
            u.setId(id);
            u.setFactoryId(FACTORY);
            u.setRoleCode(r);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(java.util.Optional.of(u));
            return u;
        });
    }

    /** 这个岗位在默认矩阵下持有这个单码 (与 IntentPermissionGate / ToolRbacGuard 同一个 hasAnyPermission)。 */
    boolean holds(String role, String code) {
        return matrix.hasAnyPermission(user(role), code);
    }

    /** 这个岗位执行这个意图绑定的工具时, 过得了 {@link ToolPermissionChain#denyReason}。工具没扫到 ⇒ false。 */
    boolean passes(String role, String intentCode, String toolName, String requiredPermission) {
        ToolExecutor tool = tools.get(toolName);
        if (tool == null) return false;
        User u = user(role);
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode(intentCode);
        intent.setToolName(toolName);
        intent.setRequiredPermission(requiredPermission);
        Map<String, Object> ctx = new HashMap<>();
        TrustedExecutionContext.enforcePrincipal(ctx, FACTORY, u.getId(), role);
        ctx = new WriteGuardService().withServerConfirmation(ctx);
        return chain.denyReason(tool, FACTORY, role, intent, ctx).isEmpty();
    }
}
