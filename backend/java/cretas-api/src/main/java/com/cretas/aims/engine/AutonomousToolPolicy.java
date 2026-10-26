package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import java.util.Map;
import java.util.Set;

/**
 * What a tool run with no interactive user behind it (cron schedule, event trigger chain) may do.
 *
 * <p>These entry points carry only a factory id: no user, no permission check, no confirmation.
 * Until a delegated grant exists for them, they may run read-only tools only. Write is decided by
 * {@link ToolAccessModes} (fail-closed: an undeclared tool counts as WRITE). Identity keys in the
 * configured parameters are refused outright, because several tools read the tenant from their
 * parameters before the context, which would let a configured step act on another factory.</p>
 */
public final class AutonomousToolPolicy {

    /** Persisted status for a scheduled run refused by this policy (column is VARCHAR(20)). */
    public static final String WRITE_DENIED = "WRITE_DENIED";
    static final String WRITE_PREFIX = "write tool not allowed without a user: ";
    static final String IDENTITY_PREFIX = "identity parameters are not accepted: ";

    static final Set<String> IDENTITY_KEYS = Set.of(
            "factoryId", "factory_id", "tenantId", "tenant_id", "userId", "user_id",
            "userRole", "user_role", "role", "roleCode", "role_code", "permissions");

    private AutonomousToolPolicy() {
    }

    /** @return null when allowed; otherwise a reason that names the tool or the offending key. */
    /** Identity keys only: a write tool may be scheduled, each of its runs then needs a delegated grant. */
    public static String identityRejection(Map<String, Object> params) {
        if (params == null) return null;
        return params.keySet().stream().filter(IDENTITY_KEYS::contains).sorted().findFirst()
                .map(key -> IDENTITY_PREFIX + key).orElse(null);
    }

    public static String rejection(String toolName, ToolExecutor tool, Map<String, Object> params) {
        if (params != null) {
            String key = params.keySet().stream().filter(IDENTITY_KEYS::contains).sorted().findFirst().orElse(null);
            if (key != null) {
                return IDENTITY_PREFIX + key;
            }
        }
        if (ToolAccessModes.isWrite(tool)) {
            return WRITE_PREFIX + toolName;
        }
        return null;
    }

    public static boolean isWriteDenial(String reason) {
        return reason != null && reason.startsWith(WRITE_PREFIX);
    }

    /**
     * Config-time mirror of the gateway's source check: a schedule/chain step is only accepted
     * when the tool's runtime policy lists this source; otherwise every run would just be
     * GATEWAY_DENIED. The gateway still enforces it at run time.
     */
    public static void requireSourceAllowed(String toolName, ToolExecutionSource source) {
        boolean allowed = RuntimePolicies.REGISTRY.findApproved(toolName)
                .map(descriptor -> descriptor.allowedSources().contains(source))
                .orElse(false);
        if (!allowed) {
            String entry = source == ToolExecutionSource.SCHEDULER ? "定时任务" : "事件链";
            throw new BusinessException(400, "工具「" + toolName + "」还没有开放给" + entry + "使用")
                    .withCode("AUTONOMOUS_SOURCE_NOT_ALLOWED")
                    .withHint("目前只开放了经过审核的只读工具（如 material_expired_query）；需要其他工具请先申请审核其运行策略");
        }
    }

    /** Loaded once; the same runtime-tool-policies.yaml the gateway resolves against. */
    private static final class RuntimePolicies {
        static final RuntimeToolDescriptorRegistry REGISTRY = RuntimeToolDescriptorRegistry.loadDefault();
    }

    /**
     * The factory user saving an enabled schedule/chain becomes its run-as user. A platform
     * admin or a user of another factory cannot: there is no factory account to run as, and the
     * gateway only accepts this factory's users.
     */
    /** The request's actor id when there is one (recorded as who revoked grants on a config edit). */
    public static Long currentActorIdOrNull() {
        return AuthenticatedActor.fromCurrentRequest().map(AuthenticatedActor::id).orElse(null);
    }

    public static Long requireRunAsUser(String factoryId) {
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentRequest()
                .orElseThrow(() -> new BusinessException(401, "无法确认是谁启用了这项自动任务，请重新登录"));
        if (actor.type() != AuthenticatedActorType.FACTORY_USER || actor.id() == null
                || factoryId == null || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "自动任务会以启用人的身份运行，只能由本厂账号启用")
                    .withCode("AUTONOMOUS_RUN_AS_FACTORY_USER_REQUIRED")
                    .withHint("请用这家工厂自己的管理员账号登录后再启用；平台账号不能作为执行人");
        }
        return actor.id();
    }

    /** Config-time refusal with the reason in words a factory admin can act on. */
    public static BusinessException refusal(String reason) {
        if (isWriteDenial(reason)) {
            String tool = reason.substring(WRITE_PREFIX.length());
            return new BusinessException(400, "定时任务和事件链没有操作人，只能使用只读工具；「" + tool + "」会写入数据")
                    .withCode("AUTONOMOUS_WRITE_TOOL_DENIED")
                    .withHint("写操作要等任务授权功能上线后才能自动执行；现在请改用只读工具，或由人员在业务页面操作");
        }
        String key = reason.startsWith(IDENTITY_PREFIX) ? reason.substring(IDENTITY_PREFIX.length()) : reason;
        return new BusinessException(400, "定时任务和事件链的参数里不能指定工厂、用户或角色：" + key)
                .withCode("AUTONOMOUS_IDENTITY_PARAMETER_DENIED")
                .withHint("执行工厂由这条配置所属的工厂决定；删掉该参数后再保存");
    }
}
