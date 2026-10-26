package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolAccessModes;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.exception.BusinessException;
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
