package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.SameNameMatches;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        throw new BusinessException(403, DENIED);
    }

    static final String NAME_PARAM = "employeeName";
    private static final String DENIED =
            "您只能查看本人的考勤; 查看其他员工的考勤需要人事读取权限 (hr:read)。";

    /** 按 id 查一位员工的考勤 (工具自己的查询)。 */
    @FunctionalInterface
    interface PerUser {
        Object query(Long userId) throws Exception;
    }

    /** 一位员工查询结果的一句话摘要, 用于同名列表。 */
    @FunctionalInterface
    interface Summary {
        String of(Object data);
    }

    /**
     * 姓名能不能决定查谁: 没有 userId, 或 userId 就是调用者本人时可以。
     * 可信上下文会把调用者自己的 userId 合进工具参数 (TrustedExecutionContext.merge), 所以「userId = 本人」
     * 不代表用户指定了人; 指定了<b>别人</b>的 userId 时仍按 userId 走 {@link #resolve}。
     */
    static boolean nameMayDecide(String requestedId, Map<String, Object> context) {
        if (requestedId == null || requestedId.isBlank()) {
            return true;
        }
        Long caller = callerId(context);
        return caller != null && requestedId.trim().equals(String.valueOf(caller));
    }

    /** 给考勤读工具的参数 schema 加上「员工姓名」。 */
    static void addNameParameter(Map<String, Object> properties) {
        Map<String, Object> name = new java.util.HashMap<>();
        name.put("type", "string");
        name.put("description", "员工姓名（如「张三」）。按姓名查时同名的员工会全部列出并标明部门/车间；"
                + "知道 userId 时优先传 userId");
        properties.put(NAME_PARAM, name);
    }

    /**
     * 口径规则 8 (Steve 2026-11-12): 「查一下张三这周的考勤」—— 同名的都查出来列给他, 标明部门/车间, 不先问。
     *
     * <p>🔴 按姓名查人是新的数据出口, 两道约束同时成立:
     * <ul>
     *   <li><b>权限</b>: 有 hr:read 才按姓名在本厂找人; 没有时只认「名字就是调用者本人」, 否则拒绝 ——
     *       拒绝前<b>不查同名</b>, 所以回复里不会泄露「有几个同名的人」。</li>
     *   <li><b>工厂范围</b>: {@code factoryId} 是工具基类从可信上下文取的, 仓储查询带 {@code factory_id} 条件;
     *       每位员工的考勤查询也仍按 {@code factoryId} 过滤。</li>
     * </ul>
     *
     * @return 完整工具结果 (message / data / {@link SameNameMatches#KEY}); 没给姓名时 null (走原来的 userId 路径)
     */
    static Map<String, Object> runByName(String requestedName, String factoryId, Map<String, Object> context,
                                         ToolRbacGuard rbacGuard, UserRepository users,
                                         PerUser query, Summary summary) throws Exception {
        if (requestedName == null || requestedName.isBlank()) {
            return null;
        }
        String name = requestedName.trim();
        List<User> matches;
        if (rbacGuard != null && rbacGuard.hasAnyPermission(context, "hr:read")) {
            matches = users.findActiveByFactoryIdAndExactFullName(factoryId, name);
        } else {
            Long caller = callerId(context);
            User self = caller == null ? null : users.findByIdAndFactoryId(caller, factoryId).orElse(null);
            if (self == null || !name.equals(self.getFullName())) {
                throw new BusinessException(403, DENIED);
            }
            matches = List.of(self);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (matches.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("employeeName", name);
            data.put("matchCount", 0);
            data.put("employees", List.of());
            result.put("data", data);
            SameNameMatches.annotate(result, "在职员工", name, 0, List.of());
            result.put("message", SameNameMatches.reply(result));
            return result;
        }
        if (matches.size() == 1) {
            User u = matches.get(0);
            Object data = query.query(u.getId());
            result.put("message", "查询成功（" + displayName(u) + "，" + distinguishing(u) + "）");
            result.put("data", data);
            SameNameMatches.annotate(result, "员工", name, 1,
                    List.of(new SameNameMatches.Match(u.getId(), displayName(u), distinguishing(u), null)));
            return result;
        }

        List<SameNameMatches.Match> listed = new ArrayList<>();
        List<Map<String, Object>> employees = new ArrayList<>();
        for (User u : matches.subList(0, Math.min(SameNameMatches.LIST_LIMIT, matches.size()))) {
            Object data = query.query(u.getId());
            listed.add(new SameNameMatches.Match(u.getId(), displayName(u), distinguishing(u), summary.of(data)));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", u.getId());
            row.put("name", displayName(u));
            row.put("department", u.getDepartment());
            row.put("position", u.getPosition());
            row.put("employeeCode", u.getEmployeeCode());
            row.put("result", data);
            employees.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employeeName", name);
        data.put("matchCount", matches.size());
        data.put("employees", employees);
        result.put("data", data);
        SameNameMatches.annotate(result, "员工", name, matches.size(), listed);
        result.put("message", SameNameMatches.reply(result));
        return result;
    }

    /** 今日打卡记录 (TimeClockRecord 或 null) 的一句话。 */
    static String todaySummary(Object data) {
        if (!(data instanceof com.cretas.aims.entity.TimeClockRecord r) || r.getClockInTime() == null) {
            return "今天还没有打卡记录";
        }
        String in = "今天上班打卡 " + hhmm(r.getClockInTime());
        return r.getClockOutTime() == null ? in + "，还没有下班打卡" : in + "，下班打卡 " + hhmm(r.getClockOutTime());
    }

    /** getClockStatus 结果 (Map) 的一句话。 */
    static String statusSummary(Object data) {
        if (data instanceof Map<?, ?> m) {
            return todaySummary(m.get("todayRecord"));
        }
        return "今天还没有打卡记录";
    }

    private static String hhmm(java.time.LocalDateTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String displayName(User u) {
        return u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName();
    }

    /** 区分字段: 部门/车间、岗位、工号; 都没有时给用户 ID, 保证同名的两行一定能分开。 */
    static String distinguishing(User u) {
        List<String> parts = new ArrayList<>();
        if (u.getDepartment() != null && !u.getDepartment().isBlank()) parts.add("车间/部门：" + u.getDepartment());
        if (u.getPosition() != null && !u.getPosition().isBlank()) parts.add("岗位：" + u.getPosition());
        if (u.getEmployeeCode() != null && !u.getEmployeeCode().isBlank()) parts.add("工号 " + u.getEmployeeCode());
        if (parts.isEmpty()) parts.add("用户ID " + u.getId());
        return String.join("，", parts);
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
