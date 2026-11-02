package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.exception.BusinessException;

import java.util.Map;

/**
 * Whose attendance an AI attendance tool may read (S6 follow-up, 2026-11-03).
 *
 * <p>The App binds the subject to the logged-in user (TimeClockController reads the JWT userId); only hr readers
 * see other employees (/time-clock/admin/history, hr:read). The model, however, may put any {@code userId} into
 * the tool arguments. So: no id or the caller's own id → the caller; another employee's id → only for callers who
 * hold hr:read, otherwise refused (never silently swapped, so the answer is not mistaken for that employee's).</p>
 */
final class AttendanceSubject {

    private AttendanceSubject() {
    }

    /**
     * @param requested the {@code userId} argument from the model (may be null / blank)
     * @param context   trusted execution context (its userId is the authenticated caller)
     * @param rbacGuard permission check against the caller's database role; null means refuse others
     */
    static Long resolve(String requested, Map<String, Object> context, ToolRbacGuard rbacGuard) {
        Long caller = callerId(context);
        if (requested == null || requested.isBlank()) {
            return caller;
        }
        long target;
        try {
            target = Long.parseLong(requested.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("userId 格式不正确");
        }
        if (caller != null && caller == target) {
            return caller;
        }
        if (rbacGuard != null && rbacGuard.hasAnyPermission(context, "hr:read")) {
            return target;
        }
        throw new BusinessException(403, "您只能查看本人的考勤; 查看其他员工的考勤需要人事读取权限 (hr:read)。");
    }

    private static Long callerId(Map<String, Object> context) {
        Object id = context == null ? null : context.get("userId");
        if (id instanceof Number n) return n.longValue();
        if (id != null) {
            try {
                return Long.parseLong(id.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
