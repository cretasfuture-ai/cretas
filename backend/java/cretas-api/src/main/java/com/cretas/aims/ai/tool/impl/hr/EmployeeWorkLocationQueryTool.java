package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerAssignmentRepository;
import com.cretas.aims.utils.DepartmentDisplayNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 查员工在哪个车间、哪条线 (意图 HR_EMPLOYEE_WORK_LOCATION_QUERY, 2026-11-17 v3 复核缺口)。
 *
 * <p>复核原话:「你说的那个老张, 哪个车间的啊」「帮我查下张三现在在哪条线」。目录里原来没有查员工的意图;
 * 已有的 {@code query_employee_profile} 返回整份 UserDTO (含手机号等), 不适合在对话里给。
 *
 * <h2>只给工作信息 (与 App 员工名录同一组字段)</h2>
 * 每个人只投影 {@link #WORK_FIELDS}: 姓名、账号、车间/部门 (中文显示名, {@link DepartmentDisplayNames#display})、今天排在哪条线。
 * 与 {@code UserController} 员工名录给的字段对齐, 不给工号、岗位。
 * 产线以当天排班为准 (WorkerAssignment → LineSchedule → ProductionLine), 没排班就明说「今天没有排班」不猜;
 * 车间取用户档案的 department。⛔ 不给「手上在做的批次」: 报工批次在 App 里只给 work_report:read, 给了就是 AI 多于 App
 * (GatedDataToolAiPathPermissionTest)。⛔ 手机号、邮箱、身份证号、工资/时薪、出生日期等一律不出 —— 投影是白名单, 不是黑名单
 * (见 {@code IntentGapV3ToolsContractTest.EmployeeWhere} 对返回字段的白名单断言)。
 *
 * <h2>拿不准就列出来</h2>
 * 只有全名完全相同且只有一位时才直接给; 同名多位、「老张」这种只给姓、或只是名字里包含 (没有全名命中) 时都不猜,
 * 返回 {@code NEEDS_SELECTION} + 候选 (姓名/账号/车间), 让用户按账号再问一次。只在本厂已启用账号里找。
 */
@Slf4j
@Component
public class EmployeeWorkLocationQueryTool extends AbstractBusinessTool {

    /** 返回给对话的员工字段白名单。 */
    static final Set<String> WORK_FIELDS = Set.of("name", "username", "department", "todayLines");

    /** 候选列表 (待选) 的字段白名单。 */
    static final Set<String> CANDIDATE_FIELDS = Set.of("name", "username", "department");

    private static final int MAX_CANDIDATES = 10;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkerAssignmentRepository workerAssignmentRepository;
    @Autowired
    private LineScheduleRepository lineScheduleRepository;
    @Autowired
    private ProductionLineRepository productionLineRepository;

    @Override
    public String getToolName() {
        return "employee_work_location_query";
    }

    @Override
    public String getDescription() {
        return "按姓名查本厂员工在哪个车间、今天排在哪条产线 (按排班)。只返回工作信息, 不返回手机号等个人信息;"
                + " 同名、只说了姓 (如「老张」) 或名字对不上全名时先列出候选让用户按账号选。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string",
                "description", "员工姓名, 可只给姓加老/小 (如 张三 / 老张)"));
        properties.put("username", Map.of("type", "string",
                "description", "账号 (候选里给的, 用来指定是哪一位)"));
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("name");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("name".equals(paramName)) {
            return "要查哪位员工? 请告诉我姓名";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String name = getString(params, "name", "").trim();
        String username = getString(params, "username");

        NameMatch match = findByName(factoryId, name);
        List<User> matches = match.users();
        boolean picked = false;
        if (username != null && !username.isBlank()) {
            String un = username.trim();
            matches = matches.stream().filter(u -> un.equals(u.getUsername())).toList();
            picked = true;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", name);
        if (matches.isEmpty()) {
            result.put("status", "NOT_FOUND");
            result.put("total", 0);
            return buildSimpleResult("本厂没有找到叫「" + name + "」的在职员工, 请核对姓名或账号。", result);
        }
        // 只有「全名完全相同且只有一位」或用户已按账号点名时才直接给; 只给姓 / 只是包含 ⇒ 哪怕只有一位也先确认。
        if (matches.size() > 1 || (!match.exact() && !picked)) {
            List<Map<String, Object>> candidates = matches.stream()
                    .limit(MAX_CANDIDATES)
                    .map(this::candidate)
                    .toList();
            result.put("status", "NEEDS_SELECTION");
            result.put("total", matches.size());
            result.put("candidates", candidates);
            String list = candidates.stream()
                    .map(c -> c.get("name") + (c.get("username") == null ? "" : "(账号 " + c.get("username") + ")")
                            + (c.get("department") == null ? "" : " " + c.get("department")))
                    .collect(Collectors.joining("、"));
            String head = matches.size() > 1
                    ? "找到 " + matches.size() + " 位「" + name + "」: "
                    : "没有全名叫「" + name + "」的员工, 最接近的是: ";
            return buildSimpleResult(head + list + "。你要查的是哪一位? 告诉我账号就行。", result);
        }

        User u = matches.get(0);
        Map<String, Object> employee = workInfo(factoryId, u);
        result.put("status", "FOUND");
        result.put("total", 1);
        result.put("employee", employee);
        return buildSimpleResult(describe(employee), result);
    }

    /** 按姓名找的结果; exact = 全名完全相同 (否则是按姓或包含找到的, 不能直接当成那个人)。 */
    record NameMatch(List<User> users, boolean exact) {
    }

    /** 本厂已启用账号里按姓名找: 全名相等优先; 其次「老张/小张」按姓; 再次全名包含。 */
    NameMatch findByName(String factoryId, String name) {
        if (name == null || name.isBlank()) {
            return new NameMatch(List.of(), false);
        }
        List<User> active = userRepository.findByFactoryIdAndIsActive(factoryId, true).stream()
                .filter(u -> Objects.equals(factoryId, u.getFactoryId()))
                .filter(u -> u.getFullName() != null && !u.getFullName().isBlank())
                .toList();
        List<User> exact = active.stream().filter(u -> name.equals(u.getFullName().trim())).toList();
        if (!exact.isEmpty()) {
            return new NameMatch(exact, true);
        }
        String surname = surnameOnly(name);
        if (surname != null) {
            return new NameMatch(active.stream().filter(u -> u.getFullName().trim().startsWith(surname))
                    .sorted(Comparator.comparing(User::getFullName)).toList(), false);
        }
        return new NameMatch(active.stream().filter(u -> u.getFullName().contains(name))
                .sorted(Comparator.comparing(User::getFullName)).toList(), false);
    }

    /** 「老张」「小李」⇒ 张 / 李; 其它返回 null。 */
    static String surnameOnly(String name) {
        if (name.length() == 2 && (name.charAt(0) == '老' || name.charAt(0) == '小')) {
            return name.substring(1);
        }
        return null;
    }

    private Map<String, Object> candidate(User u) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", u.getFullName());
        c.put("username", u.getUsername());
        // 与 App 员工名录同一个翻译 (UserController 名录 / UserMapper): 英文部门码给中文显示名, ⛔ 不原样给 production
        c.put("department", DepartmentDisplayNames.display(u.getDepartment()));
        return c;
    }

    private Map<String, Object> workInfo(String factoryId, User u) {
        Map<String, Object> e = candidate(u);
        e.put("todayLines", todayLines(factoryId, u.getId()));
        return e;
    }

    /** 今天排班里分到的产线 (只认本厂的线)。 */
    private List<Map<String, Object>> todayLines(String factoryId, Long userId) {
        List<WorkerAssignment> assignments = workerAssignmentRepository.findByUserIdAndDate(userId, LocalDate.now());
        if (assignments.isEmpty()) {
            return List.of();
        }
        List<String> scheduleIds = assignments.stream().map(WorkerAssignment::getScheduleId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, WorkerAssignment> bySchedule = new LinkedHashMap<>();
        assignments.forEach(a -> bySchedule.putIfAbsent(a.getScheduleId(), a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LineSchedule s : lineScheduleRepository.findAllById(scheduleIds)) {
            if (s.getProductionLineId() == null) {
                continue;
            }
            ProductionLine line = productionLineRepository
                    .findByIdAndFactoryIdAndDeletedAtIsNull(s.getProductionLineId(), factoryId).orElse(null);
            if (line == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineName", line.getName());
            m.put("plannedStart", s.getPlannedStartTime());
            m.put("plannedEnd", s.getPlannedEndTime());
            WorkerAssignment a = bySchedule.get(s.getId());
            m.put("assignmentStatus", a == null || a.getStatus() == null ? null : a.getStatus().name());
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String describe(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.get("name"));
        if (e.get("username") != null) {
            sb.append(" (账号 ").append(e.get("username")).append(")");
        }
        sb.append(": 车间/部门 ").append(e.get("department") == null ? "未登记" : e.get("department"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) e.get("todayLines");
        if (lines.isEmpty()) {
            sb.append("; 今天没有排班, 不知道在哪条线 (排班里没有分到产线)");
        } else {
            sb.append("; 今天排在 ").append(lines.stream().map(l -> String.valueOf(l.get("lineName")))
                    .distinct().collect(Collectors.joining("、")));
        }
        sb.append("。");
        return sb.toString();
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * 与 App 员工名录 (UserRepository#findByFactoryIdAndIsActive 的读接口) 同宽: production / procurement / sales 读;
     * 加 scheduling:read 看当天产线排程 ({@code SchedulingController})。不含 hr:read —— 那份名录不给只有 hr 读的角色
     * (hr_admin); 不多也不少于 App, 由 GatedDataToolAiPathPermissionTest 钉住。
     */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("production:read", "procurement:read", "sales:read", "scheduling:read");
    }
}
