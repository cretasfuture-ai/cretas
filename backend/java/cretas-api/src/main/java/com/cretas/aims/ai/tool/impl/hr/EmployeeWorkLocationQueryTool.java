package com.cretas.aims.ai.tool.impl.hr;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.BatchWorkSession;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkerAssignment;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerAssignmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * <h2>只给工作信息</h2>
 * 每个人只投影 {@link #WORK_FIELDS}: 姓名、工号、车间/部门、岗位、今天排在哪条线、手上正在做的批次。
 * ⛔ 手机号、邮箱、身份证号、工资/时薪、出生日期等一律不出 —— 投影是白名单, 不是黑名单
 * (见 {@code EmployeeWorkLocationQueryToolTest} 对返回字段的白名单断言)。
 *
 * <h2>同名先列出来</h2>
 * 按姓名匹配到多个人 (或「老张」这种只给姓) 时不猜, 返回 {@code NEEDS_SELECTION} + 候选 (姓名/工号/车间/岗位),
 * 让用户按工号再问一次。只在本厂已启用账号里找。
 */
@Slf4j
@Component
public class EmployeeWorkLocationQueryTool extends AbstractBusinessTool {

    /** 返回给对话的员工字段白名单。 */
    static final Set<String> WORK_FIELDS = Set.of(
            "name", "employeeCode", "department", "position", "todayLines", "currentBatches");

    /** 候选列表 (同名待选) 的字段白名单。 */
    static final Set<String> CANDIDATE_FIELDS = Set.of("name", "employeeCode", "department", "position");

    private static final int MAX_CANDIDATES = 10;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkerAssignmentRepository workerAssignmentRepository;
    @Autowired
    private LineScheduleRepository lineScheduleRepository;
    @Autowired
    private ProductionLineRepository productionLineRepository;
    @Autowired
    private BatchWorkSessionRepository batchWorkSessionRepository;

    @Override
    public String getToolName() {
        return "employee_work_location_query";
    }

    @Override
    public String getDescription() {
        return "按姓名查本厂员工现在在哪个车间、哪条产线、什么岗位、手上在做哪个批次。只返回工作信息, 不返回手机号等个人信息;"
                + " 同名或只说了姓 (如「老张」) 时先列出候选让用户按工号选。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string",
                "description", "员工姓名, 可只给姓加老/小 (如 张三 / 老张)"));
        properties.put("employeeCode", Map.of("type", "string",
                "description", "工号 (同名时用来指定是哪一位)"));
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
        String employeeCode = getString(params, "employeeCode");

        List<User> matches = findByName(factoryId, name);
        if (employeeCode != null && !employeeCode.isBlank()) {
            String code = employeeCode.trim();
            matches = matches.stream().filter(u -> code.equals(u.getEmployeeCode())).toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", name);
        if (matches.isEmpty()) {
            result.put("status", "NOT_FOUND");
            result.put("total", 0);
            return buildSimpleResult("本厂没有找到叫「" + name + "」的在职员工, 请核对姓名或工号。", result);
        }
        if (matches.size() > 1) {
            List<Map<String, Object>> candidates = matches.stream()
                    .limit(MAX_CANDIDATES)
                    .map(this::candidate)
                    .toList();
            result.put("status", "NEEDS_SELECTION");
            result.put("total", matches.size());
            result.put("candidates", candidates);
            String list = candidates.stream()
                    .map(c -> c.get("name") + (c.get("employeeCode") == null ? "" : "(工号 " + c.get("employeeCode") + ")")
                            + (c.get("department") == null ? "" : " " + c.get("department")))
                    .collect(Collectors.joining("、"));
            return buildSimpleResult("找到 " + matches.size() + " 位「" + name + "」: " + list
                    + "。你要查的是哪一位? 告诉我工号就行。", result);
        }

        User u = matches.get(0);
        Map<String, Object> employee = workInfo(factoryId, u);
        result.put("status", "FOUND");
        result.put("total", 1);
        result.put("employee", employee);
        return buildSimpleResult(describe(employee), result);
    }

    /** 本厂已启用账号里按姓名找: 全名相等优先; 其次全名包含; 「老张/小张」按姓找。 */
    List<User> findByName(String factoryId, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<User> active = userRepository.findByFactoryIdAndIsActive(factoryId, true).stream()
                .filter(u -> Objects.equals(factoryId, u.getFactoryId()))
                .filter(u -> u.getFullName() != null && !u.getFullName().isBlank())
                .toList();
        List<User> exact = active.stream().filter(u -> name.equals(u.getFullName().trim())).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        String surname = surnameOnly(name);
        if (surname != null) {
            return active.stream().filter(u -> u.getFullName().trim().startsWith(surname))
                    .sorted(Comparator.comparing(User::getFullName)).toList();
        }
        return active.stream().filter(u -> u.getFullName().contains(name))
                .sorted(Comparator.comparing(User::getFullName)).toList();
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
        c.put("employeeCode", u.getEmployeeCode());
        c.put("department", u.getDepartment());
        c.put("position", u.getPosition());
        return c;
    }

    private Map<String, Object> workInfo(String factoryId, User u) {
        Map<String, Object> e = candidate(u);
        e.put("todayLines", todayLines(factoryId, u.getId()));
        e.put("currentBatches", currentBatches(factoryId, u.getId()));
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

    /** 今天正在做 (已分配/进行中、未签退) 的本厂生产批次。 */
    private List<Map<String, Object>> currentBatches(String factoryId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<BatchWorkSession> sessions = batchWorkSessionRepository
                .findByEmployeeIdAndTimeRange(userId, now.toLocalDate().atStartOfDay(), now);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BatchWorkSession s : sessions) {
            if (!"assigned".equals(s.getStatus()) && !"working".equals(s.getStatus())) {
                continue;
            }
            if (s.getCheckOutTime() != null) {
                continue;
            }
            ProductionBatch b = s.getBatch();
            if (b == null || !factoryId.equals(b.getFactoryId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchNumber", b.getBatchNumber());
            m.put("productName", b.getProductName());
            m.put("since", s.getCheckInTime());
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String describe(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.get("name"));
        if (e.get("employeeCode") != null) {
            sb.append(" (工号 ").append(e.get("employeeCode")).append(")");
        }
        sb.append(": 车间/部门 ").append(e.get("department") == null ? "未登记" : e.get("department"));
        sb.append(", 岗位 ").append(e.get("position") == null ? "未登记" : e.get("position"));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) e.get("todayLines");
        if (lines.isEmpty()) {
            sb.append("; 今天排班里没有分到产线");
        } else {
            sb.append("; 今天排在 ").append(lines.stream().map(l -> String.valueOf(l.get("lineName")))
                    .distinct().collect(Collectors.joining("、")));
        }
        List<Map<String, Object>> batches = (List<Map<String, Object>>) e.get("currentBatches");
        if (!batches.isEmpty()) {
            sb.append("; 手上在做 ").append(batches.stream()
                    .map(b -> b.get("batchNumber") + (b.get("productName") == null ? "" : "(" + b.get("productName") + ")"))
                    .collect(Collectors.joining("、")));
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
     * 员工姓名/车间/岗位在 App 里经人事读 (员工档案) 给到; 排班分到哪条线经排程读
     * ({@code SchedulingController} scheduling:read / production:read) 给到。取两者之并, 不比 App 更宽。
     */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("hr:read", "hr:read_write", "scheduling:read", "production:read");
    }
}
