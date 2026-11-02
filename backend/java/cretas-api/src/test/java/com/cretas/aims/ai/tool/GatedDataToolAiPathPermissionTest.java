package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S6 acceptance (ERP-S6-02-SKILL-ROLE-BYPASS-001): a production manager ran the monthly-financial-close
 * Skill and read the balance sheet, while {@code GET finance/report/balance-sheet} is 403 for the same
 * person: tools call services directly, so a controller's gate never runs on the AI / Skill / MCP path.
 *
 * <p>Rule ("AI 能做的 = App 里本来就能做的"): an AI path never returns more than the caller can read
 * from the direct API. {@link GatedDataScan} reads the bytecode: for every GET handler it records the
 * service / repository methods it calls and its effective gate; for every tool the same calls. Every
 * READ tool is then classified, failing closed:</p>
 * <ul>
 *   <li>it calls data that only gated read endpoints serve → it must declare permission codes that each
 *       of those endpoints accepts (any-of; a requireAll endpoint cannot be mirrored and fails), or, when
 *       the endpoints are role-gated only, a role gate admitting no role they do not admit;</li>
 *   <li>it calls only data some ungated read endpoint also serves → nothing to add;</li>
 *   <li>no read endpoint serves anything it calls → it must declare a gate, or be registered with a
 *       reason in {@code ai/tool/gated-data-registrations.tsv}.</li>
 *   <li>SURFACE registration: the same data reaches the user through other read endpoints than the ones the
 *       scan links (e.g. lineage via {@code TraceabilityController}, not the admin canvas); the tool's gate
 *       then equals those endpoints' gate exactly (open endpoints ⇒ no gate), checked by handler name.</li>
 * </ul>
 * An unclassified READ tool, or a stale registration, fails. The same applies to every tool of every
 * built-in Skill (declared tools, DAG nodes and fallbacks).
 */
@DisplayName("受控数据: AI / Skill 路径不多于直接接口 (按方法扫描, 未分类即失败)")
public class GatedDataToolAiPathPermissionTest {

    private static final String FACTORY = "F006";
    private static final long PRODUCTION_MANAGER = 9501L;
    private static final long FINANCE_MANAGER = 9502L;
    private static final long SUPER_ADMIN = 9503L;

    private static GatedDataScan scan;
    private static Map<String, String[]> registrations;

    @BeforeAll
    static void scanOnce() throws Exception {
        scan = GatedDataScan.run();
        registrations = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                GatedDataToolAiPathPermissionTest.class.getResourceAsStream("/ai/tool/gated-data-registrations.tsv"),
                StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cols = line.split("\t", 3);
                registrations.put(cols[0], new String[]{cols[1], cols.length > 2 ? cols[2] : ""});
            }
        }
    }

    private UserRepository users;
    private PermissionService permissions;
    private ToolRbacEnforcer enforcer;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        permissions = mock(PermissionService.class);
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        when(users.findById(PRODUCTION_MANAGER)).thenReturn(Optional.of(user(PRODUCTION_MANAGER, "production_manager")));
        when(users.findById(FINANCE_MANAGER)).thenReturn(Optional.of(user(FINANCE_MANAGER, "finance_manager")));
        when(users.findById(SUPER_ADMIN)).thenReturn(Optional.of(user(SUPER_ADMIN, "factory_super_admin")));
        // Mirrors the real matrix for these three: super admin holds everything; finance_manager holds
        // finance:* (not hr:*, not sales:view); production_manager holds production:* only.
        when(permissions.hasAnyPermission(any(), any(String[].class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            Set<String> asked = new HashSet<>();
            Object[] args = call.getArguments();
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof String code) asked.add(code);
                if (args[i] instanceof String[] codes) asked.addAll(Arrays.asList(codes));
            }
            if (user.getId() == SUPER_ADMIN) return true;
            Set<String> held = user.getId() == FINANCE_MANAGER
                    ? Set.of("finance:read", "finance:read_write", "sales:read", "analytics:read", "analytics:read_write")
                    : Set.of("production:read", "production:read_write");
            return asked.stream().anyMatch(held::contains);
        });
    }

    // ==================== classification (fail closed) ====================

    @Test
    @DisplayName("🔴 每个 READ 工具都已分类: 读受控数据的挂了与直接接口一致的门, 无对位接口的登记了理由")
    void everyReadToolIsClassified() {
        // Readings that must happen, or this gate guards nothing.
        assertThat(scan.uninstantiable).as("tools the scan cannot name are invisible to it").isEmpty();
        assertThat(scan.toolCalls).hasSizeGreaterThan(500);
        assertThat(scan.handlers).isGreaterThan(1000);
        for (String known : List.of("balance_sheet_query", "attendance_stats", "purchase_statistics", "order_list")) {
            assertThat(scan.gatedCalls(known)).as("%s must read gated data per the scan", known).isNotEmpty();
        }

        List<String> problems = new ArrayList<>();
        for (String tool : scan.toolCalls.keySet()) {
            ToolExecutor instance = scan.instances.get(tool);
            if (ToolAccessModes.isWrite(instance) && !registrations.containsKey(tool)) continue;
            String problem = classify(tool, instance);
            if (problem != null) problems.add(tool + ": " + problem);
        }
        assertThat(problems).as("unclassified or wrongly gated READ tools").isEmpty();
    }

    @Test
    @DisplayName("gated-data-declared.tsv 与工具源码里的声明逐条一致 (描述清册测试读它)")
    void theDeclaredListMatchesTheTools() throws Exception {
        Map<String, String> listed = new TreeMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                GatedDataToolAiPathPermissionTest.class.getResourceAsStream("/ai/tool/gated-data-declared.tsv"),
                StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cols = line.split("	");
                listed.put(cols[0], cols[1] + ":" + cols[2]);
            }
        }
        assertThat(listed).hasSizeGreaterThan(100);
        for (Map.Entry<String, String> entry : listed.entrySet()) {
            ToolExecutor tool = scan.instances.get(entry.getKey());
            assertThat(tool).as(entry.getKey()).isNotNull();
            String actual = declared(tool).isEmpty()
                    ? "ROLES:" + String.join(",", allowedRoles(tool))
                    : "CODES:" + String.join(",", new TreeSet<>(declared(tool)));
            assertThat(actual).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("登记表没有过期条目, 每条都有理由")
    void registrationsAreCurrentAndReasoned() {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : registrations.entrySet()) {
            String tool = entry.getKey();
            String kind = entry.getValue()[0];
            assertThat(entry.getValue()[1]).as("reason for %s", tool).hasSizeGreaterThan(20);
            ToolExecutor instance = scan.instances.get(tool);
            if (instance == null) { stale.add(tool + " (no such tool)"); continue; }
            if ("NO_COUNTERPART".equals(kind) && (scan.hasCounterpart(tool) || hasGate(tool, instance))) {
                stale.add(tool + " (no longer without counterpart / gate)");
            }
            if ("RULED".equals(kind) && declared(instance).isEmpty()) {
                stale.add(tool + " (ruled codes not declared)");
            }
            if ("SURFACE".equals(kind)) {
                for (String handler : surfaceHandlers(entry.getValue()[1])) {
                    if (!scan.readEndpoints.containsKey(handler)) stale.add(tool + " (no read handler " + handler + ")");
                }
                if (scan.gatedCalls(tool).isEmpty()) stale.add(tool + " (reads no gated data: SURFACE not needed)");
            }
        }
        assertThat(stale).isEmpty();
    }

    @Test
    @DisplayName("🔴 内置 Skill 的每个工具 (含 DAG 节点与备选) 都通过同一分类")
    void everyBuiltInSkillToolIsClassified() {
        SkillRegistryImpl registry = defaultSkills();
        List<SkillDefinition> skills = registry.getAllSkills();
        assertThat(skills).hasSizeGreaterThan(10);
        List<String> problems = new ArrayList<>();
        for (SkillDefinition skill : skills) {
            Set<String> tools = new LinkedHashSet<>();
            if (skill.getTools() != null) tools.addAll(skill.getTools());
            if (skill.getExecutionGraph() != null) {
                for (SkillDefinition.ExecutionNode node : skill.getExecutionGraph()) {
                    if (node.getToolName() != null) tools.add(node.getToolName());
                    if (node.getFallbackTool() != null) tools.add(node.getFallbackTool());
                }
            }
            for (String tool : tools) {
                ToolExecutor instance = scan.instances.get(tool);
                if (instance == null || (ToolAccessModes.isWrite(instance) && !registrations.containsKey(tool))) continue;
                String problem = classify(tool, instance);
                if (problem != null) problems.add(skill.getName() + " → " + tool + ": " + problem);
            }
        }
        assertThat(problems).isEmpty();
        for (String skill : List.of("monthly-financial-close", "cost-analysis", "personnel-scheduling")) {
            assertThat(registry.getSkill(skill).orElseThrow().getTools())
                    .as("%s reads gated data: some of its tools must carry a gate", skill)
                    .anySatisfy(tool -> assertThat(hasGate(tool, scan.instances.get(tool))).isTrue());
        }
    }

    /** @return null when classified, otherwise what is wrong */
    private static String classify(String tool, ToolExecutor instance) {
        Map<GatedDataScan.DataMethod, Set<String>> gated = scan.gatedCalls(tool);
        String[] registration = registrations.get(tool);
        Set<String> codes = declared(instance);
        if (codes.isEmpty()) {
            Set<String> mapped = ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(tool);
            if (mapped != null) codes = mapped;
        }
        if (!gated.isEmpty()) {
            for (GatedDataScan.DataMethod data : gated.keySet()) {
                List<GatedDataScan.Endpoint> endpoints = scan.servedBy.get(data);
                if (endpoints.stream().allMatch(e -> e.requireAll() && e.codes().size() > 1)) {
                    return data + " is served only by requireAll endpoints " + endpoints
                            + ": an any-of tool gate cannot mirror them";
                }
            }
            if (registration != null && "RULED".equals(registration[0])) {
                return codes.isEmpty() ? "ruled but declares no codes" : null;
            }
            if (registration != null && "SURFACE".equals(registration[0])) {
                return surfaceMismatch(instance, codes, surfaceHandlers(registration[1]));
            }
            Set<String> admitAll = null;
            for (Set<String> admit : gated.values()) {
                admitAll = admitAll == null ? new TreeSet<>(admit) : intersect(admitAll, admit);
            }
            if (!codes.isEmpty()) {
                return admitAll.containsAll(codes) ? null
                        : "declares " + codes + " but the direct endpoints admit only " + admitAll + " for " + gated.keySet();
            }
            if (roleGated(instance)) {
                Set<String> allowed = allowedRoles(instance);
                for (GatedDataScan.DataMethod data : gated.keySet()) {
                    Set<String> roles = new HashSet<>();
                    for (GatedDataScan.Endpoint e : scan.servedBy.get(data)) roles.addAll(e.roles());
                    Set<String> permitted = roles.isEmpty() ? Set.of("factory_super_admin") : roles;
                    if (!permitted.containsAll(allowed)) {
                        return "role gate admits " + allowed + " but " + data + " is served only to " + permitted;
                    }
                }
                return allowed.isEmpty() ? "role gate admits nobody" : null;
            }
            return "reads " + gated.keySet() + " (served only by gated endpoints " + admitAll + ") with no gate";
        }
        if (scan.hasCounterpart(tool)) return null;
        if (hasGate(tool, instance)) return null;
        return registration != null && "NO_COUNTERPART".equals(registration[0]) ? null
                : "no read endpoint serves its data and it has neither a gate nor a registration";
    }

    /** SURFACE reason column: {@code Controller#method[,Controller#method] — why}. */
    private static List<String> surfaceHandlers(String reason) {
        String head = reason.split(" ", 2)[0];
        return Arrays.asList(head.split(","));
    }

    /** The tool's gate must equal the surface endpoints' gate: same codes, same roles, or none when they are open. */
    private static String surfaceMismatch(ToolExecutor instance, Set<String> codes, List<String> handlers) {
        Set<String> surfaceCodes = new TreeSet<>();
        Set<String> surfaceRoles = new TreeSet<>();
        for (String handler : handlers) {
            GatedDataScan.Endpoint e = scan.readEndpoints.get(handler);
            if (e == null) return "no read handler " + handler;
            if (e.requireAll() && e.codes().size() > 1) return handler + " is requireAll: an any-of gate cannot mirror it";
            surfaceCodes.addAll(e.codes());
            surfaceRoles.addAll(e.roles());
        }
        Set<String> toolRoles = roleGated(instance) ? allowedRoles(instance) : Set.of();
        if (!new TreeSet<>(codes).equals(surfaceCodes)) {
            return "declares codes " + new TreeSet<>(codes) + " but " + handlers + " admit " + surfaceCodes;
        }
        if (!new TreeSet<>(toolRoles).equals(surfaceRoles)) {
            return "role gate " + new TreeSet<>(toolRoles) + " but " + handlers + " admit roles " + surfaceRoles;
        }
        return null;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> r = new TreeSet<>(a);
        r.retainAll(b);
        return r;
    }

    private static Set<String> declared(ToolExecutor tool) {
        Set<String> codes = tool == null ? null : tool.getRequiredPermissions();
        return codes == null ? Set.of() : codes;
    }

    private static boolean roleGated(ToolExecutor tool) {
        return tool.requiresPermission() && declared(tool).isEmpty() && !tool.hasPermission("unknown_role");
    }

    private static Set<String> allowedRoles(ToolExecutor tool) {
        return Arrays.stream(FactoryUserRole.values()).map(Enum::name).filter(tool::hasPermission)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean hasGate(String tool, ToolExecutor instance) {
        if (instance == null) return false;
        Set<String> mapped = ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(tool);
        return !declared(instance).isEmpty() || (mapped != null && !mapped.isEmpty()) || roleGated(instance);
    }

    // ==================== AI path and Skill path ====================

    @Test
    @DisplayName("生产经理经 AI 查资产负债表 / 考勤 / 成本趋势等 → 拒绝; 财务经理查资产负债表 → 放行")
    void productionManagerIsDeniedOnTheAiPathForGatedData() {
        com.cretas.aims.service.intent.FrontlineAiWriteGrantService frontline =
                mock(com.cretas.aims.service.intent.FrontlineAiWriteGrantService.class);
        when(frontline.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        ToolPermissionChain chain = new ToolPermissionChain(enforcer, frontline);
        for (String tool : List.of("balance_sheet_query", "income_statement_query", "attendance_stats",
                "report_cost_trend_analysis", "purchase_statistics", "order_list", "wage_policy_query")) {
            ToolExecutor real = scan.instances.get(tool);
            assertThat(chain.denyReason(real, FACTORY, "production_manager", null,
                    ctx(PRODUCTION_MANAGER, "production_manager"))).as("production_manager on %s", tool).isPresent();
        }
        assertThat(chain.denyReason(scan.instances.get("balance_sheet_query"), FACTORY, "finance_manager", null,
                ctx(FINANCE_MANAGER, "finance_manager"))).isEmpty();
        // Control: a tool whose data is served ungated stays open.
        assertThat(chain.denyReason(scan.instances.get("attendance_today"), FACTORY, "production_manager", null,
                ctx(PRODUCTION_MANAGER, "production_manager"))).isEmpty();
    }

    @Test
    @DisplayName("Skill 里只要有一个工具读不了, 整个 Skill 拒绝且一个工具都不执行 (月度复盘 / 成本分析 / 人员排班)")
    void aSkillWithAnyToolTheCallerMayNotUseIsRefusedWhole() throws Exception {
        SkillRegistryImpl registry = defaultSkills();
        for (String skillName : List.of("monthly-financial-close", "cost-analysis", "personnel-scheduling")) {
            SkillDefinition skill = registry.getSkill(skillName).orElseThrow();
            List<String> executed = new ArrayList<>();
            SkillExecutorImpl executor = executorFor(skill, executed);
            SkillResult refused = executor.execute(skill, context(PRODUCTION_MANAGER, "production_manager"));
            assertThat(refused.isPermissionDenied()).as("%s for production_manager", skillName).isTrue();
            assertThat(executed).as("%s ran tools", skillName).isEmpty();
        }
        // finance_manager lacks hr / sales:view (wage, funnel, commission): month-end close is refused whole too.
        SkillDefinition close = registry.getSkill("monthly-financial-close").orElseThrow();
        List<String> executed = new ArrayList<>();
        assertThat(executorFor(close, executed).execute(close, context(FINANCE_MANAGER, "finance_manager"))
                .isPermissionDenied()).isTrue();
        assertThat(executed).isEmpty();
        // Positive control: the super admin runs it.
        SkillResult allowed = executorFor(close, executed).execute(close, context(SUPER_ADMIN, "factory_super_admin"));
        assertThat(allowed.isPermissionDenied()).isFalse();
        assertThat(executed).isNotEmpty();
    }

    @Test
    @DisplayName("Skill 入口把无权限拒绝映射成 403")
    void theRegisteredSkillEntryAnswers403OnAPermissionRefusal() {
        com.cretas.aims.service.skill.contract.SavedSkillService service =
                mock(com.cretas.aims.service.skill.contract.SavedSkillService.class);
        SkillResult refused = SkillResult.error("monthly-financial-close", "你没有运行所需的权限", List.of(), 1L);
        refused.setPermissionDenied(true);
        when(service.runRegistered(any(), any(), any(), any(), any(), any(), any())).thenReturn(refused);
        com.cretas.aims.controller.SavedSkillController controller =
                new com.cretas.aims.controller.SavedSkillController(service);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(com.cretas.aims.security.AuthenticatedActor.REQUEST_ATTRIBUTE,
                new com.cretas.aims.security.AuthenticatedActor(
                        com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, PRODUCTION_MANAGER, FACTORY,
                        "production_manager"));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            assertThatThrownBy(() -> controller.runRegistered(FACTORY, "monthly-financial-close", null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    // ==================== helpers ====================

    private SkillExecutorImpl executorFor(SkillDefinition skill, List<String> executed) {
        ToolRegistry tools = mock(ToolRegistry.class);
        for (String toolName : skill.getTools()) {
            ToolExecutor real = scan.instances.get(toolName);
            when(tools.getExecutor(toolName)).thenReturn(Optional.of(recording(real, toolName, executed)));
            when(tools.hasExecutor(toolName)).thenReturn(true);
        }
        when(tools.isToolEnabledForFactory(any(), any())).thenReturn(true);
        DashScopeClient llm = mock(DashScopeClient.class);
        when(llm.chat(any(), any())).thenReturn("{\"tools_to_execute\":[]}");
        SkillExecutorImpl executor = new SkillExecutorImpl(tools, llm, new ObjectMapper());
        com.cretas.aims.service.intent.FrontlineAiWriteGrantService frontline =
                mock(com.cretas.aims.service.intent.FrontlineAiWriteGrantService.class);
        when(frontline.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(executor, "toolPermissionChain", new ToolPermissionChain(enforcer, frontline));
        ReflectionTestUtils.setField(executor, "writeGuardService", mock(WriteGuardService.class));
        ReflectionTestUtils.setField(executor, "maxToolsPerSkill", 10);
        return executor;
    }

    /** The real tool's gate (codes / roles), with execution recorded instead of performed. */
    private static ToolExecutor recording(ToolExecutor real, String name, List<String> executed) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public AccessMode getAccessMode() { return AccessMode.READ; }
            @Override public Set<String> getRequiredPermissions() { return declared(real); }
            @Override public boolean requiresPermission() { return real != null && real.requiresPermission(); }
            @Override public boolean hasPermission(String role) { return real == null || real.hasPermission(role); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) {
                executed.add(name);
                return "{\"success\":true}";
            }
        };
    }

    /** The registry as the application builds it: built-in Skills plus SKILL.md files, no database Skills. */
    public static SkillRegistryImpl defaultSkills() {
        SmartBiSkillRepository skillRepository = mock(SmartBiSkillRepository.class);
        when(skillRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());
        SkillRegistryImpl registry = new SkillRegistryImpl(skillRepository, new ObjectMapper());
        ReflectionTestUtils.setField(registry, "skillsDirectory", "classpath:skills/");
        ReflectionTestUtils.setField(registry, "loadDefaults", true);
        ReflectionTestUtils.setField(registry, "defaultMinMatchScore", 0.1);
        ReflectionTestUtils.setField(registry, "factorySkillConfigRepository",
                mock(com.cretas.aims.repository.config.FactorySkillConfigRepository.class));
        registry.init();
        return registry;
    }

    private static SkillContext context(long userId, String role) {
        return SkillContext.builder().factoryId(FACTORY).userId(String.valueOf(userId)).userRole(role)
                .sessionId("s-" + userId).userQuery("本月经营怎么样").extractedParams(new HashMap<>()).build();
    }

    private static Map<String, Object> ctx(long userId, String role) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", userId);
        c.put("userRole", role);
        return c;
    }

    private static User user(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(FACTORY);
        u.setRoleCode(role);
        return u;
    }
}
