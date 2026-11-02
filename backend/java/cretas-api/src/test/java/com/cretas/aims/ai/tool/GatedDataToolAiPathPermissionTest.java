package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.impl.PermissionServiceImpl;
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
 * from the direct API. {@link GatedDataScan} reads the bytecode: for every controller handler it records the
 * service / repository methods it calls and its effective gate; for every tool the same calls. Every tool,
 * READ and WRITE, is then classified per data method it calls, failing closed (strict sweep, 2026-11-03):</p>
 * <ul>
 *   <li>served only by gated read endpoints → a requirement: those endpoints' gate;</li>
 *   <li>served by some open read endpoint → nothing;</li>
 *   <li>served by no read endpoint → its owner's row in {@code ai/tool/gated-data-domains.tsv} decides (GATE → the
 *       listed read handlers' gate; OPEN / INTERNAL / NO_APP_PAGE → nothing); an owner with no row keeps the gate
 *       of the read endpoints that call its other methods; an owner nothing reads and no row covers fails.</li>
 *   <li>WRITE tools: a data method only write endpoints call is a write, outside this sweep; one a read endpoint
 *       serves is judged against read and write endpoints together (a write endpoint reads on its way too).</li>
 * </ul>
 * The tool's own gate (declared / mapped codes, legacy role gate, {@link ToolExecutor#getRequiredRoles}) is compared
 * as roles under the real default {@code PermissionServiceImpl} matrix: it may admit no role a requirement refuses.
 * RULED / SURFACE registrations in {@code gated-data-registrations.tsv} replace the derivation for a named tool.
 * The same applies to every tool of every built-in Skill (declared tools, DAG nodes and fallbacks).
 */
@DisplayName("受控数据: AI / Skill 路径不多于直接接口 (按方法扫描, 未分类即失败)")
public class GatedDataToolAiPathPermissionTest {

    private static final String FACTORY = "F006";
    private static final long PRODUCTION_MANAGER = 9501L;
    private static final long FINANCE_MANAGER = 9502L;
    private static final long SUPER_ADMIN = 9503L;

    static GatedDataScan scan;
    private static Map<String, String[]> registrations;
    private static List<Domain> domains;

    /**
     * A row of {@code gated-data-domains.tsv}: data owners (repositories / services) whose methods a tool calls
     * but no read endpoint calls. GATE: the listed read handlers serve that data domain in the app, and their
     * gate applies; OPEN: the listed read handlers serve it ungated; INTERNAL: no business data (reason says why).
     */
    private record Domain(String name, String kind, java.util.regex.Pattern owners,
                          List<String> handlers, String reason, List<GatedDataScan.Endpoint> derived) {
        List<GatedDataScan.Endpoint> endpoints() {
            return derived != null ? derived : handlers.stream().map(scan.readEndpoints::get).toList();
        }
    }

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
        domains = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                GatedDataToolAiPathPermissionTest.class.getResourceAsStream("/ai/tool/gated-data-domains.tsv"),
                StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] cols = line.split("	", 5);
                List<String> handlers = cols[3].equals("-") ? List.of() : Arrays.asList(cols[3].split(","));
                domains.add(new Domain(cols[0], cols[1], java.util.regex.Pattern.compile(cols[2]), handlers,
                        cols.length > 4 ? cols[4] : "", null));
            }
        }
    }

    private static Domain domainOf(String owner) {
        // Nested result types (Service$Result) belong to their service.
        String base = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
        for (Domain d : domains) {
            if (d.owners().matcher(base).matches()) return d;
        }
        // No row: an owner the app already reads through other methods keeps those read endpoints' gate
        // (same repository / service = same data); an owner no read endpoint ever touches needs a row.
        List<GatedDataScan.Endpoint> served = scan.readEndpointsOfOwner(owner);
        if (served.isEmpty() && !base.equals(owner)) served = scan.readEndpointsOfOwner(base);
        if (served.isEmpty()) return null;
        boolean allGated = served.stream().allMatch(GatedDataScan.Endpoint::gated);
        return new Domain("derived:" + base, allGated ? "GATE" : "OPEN", null, List.of(), "derived", served);
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
    @DisplayName("🔴 每个工具 (读写都算) 读的每个数据方法都已分类: 受控的挂了与直接接口一致的门, 无接口的按数据域表")
    void everyToolIsClassified() {
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
            String problem = classify(tool, instance);
            if (problem != null) problems.add(tool + ": " + problem);
        }
        assertThat(problems).as("unclassified or wrongly gated tools").isEmpty();
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
                listed.put(cols[0], cols[1] + ":" + cols[2] + (cols.length > 3 ? "|" + cols[3] : ""));
            }
        }
        assertThat(listed).hasSizeGreaterThan(100);
        for (Map.Entry<String, String> entry : listed.entrySet()) {
            ToolExecutor tool = scan.instances.get(entry.getKey());
            assertThat(tool).as(entry.getKey()).isNotNull();
            String actual = !declared(tool).isEmpty() ? "CODES:" + String.join(",", new TreeSet<>(declared(tool)))
                    : roleGated(tool) ? "ROLES:" + String.join(",", allowedRoles(tool))
                    : "BASE:-";
            Set<String> requiredRoles = tool.getRequiredRoles();
            if (requiredRoles != null && !requiredRoles.isEmpty()) {
                actual += "|REQ_ROLES:" + String.join(",", new TreeSet<>(requiredRoles));
            }
            assertThat(actual).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("数据域表: 每行的接口真实存在且门与类别一致, 每行都在用, 每行都有理由")
    void domainRowsAreRealUsedAndReasoned() {
        assertThat(domains).hasSizeGreaterThan(10);
        Set<String> unservedOwners = new TreeSet<>();
        scan.toolCalls.values().forEach(calls -> calls.stream()
                .filter(d -> scan.servedBy.get(d) == null || scan.servedBy.get(d).isEmpty())
                .forEach(d -> unservedOwners.add(d.owner().contains("$") ? d.owner().substring(0, d.owner().indexOf('$')) : d.owner())));
        List<String> problems = new ArrayList<>();
        for (Domain d : domains) {
            if (d.reason().length() < 10) problems.add(d.name() + ": no reason");
            if (!Set.of("GATE", "OPEN", "INTERNAL", "NO_APP_PAGE").contains(d.kind())) problems.add(d.name() + ": unknown kind " + d.kind());
            boolean needsHandlers = Set.of("GATE", "OPEN").contains(d.kind());
            if (needsHandlers && d.handlers().isEmpty()) problems.add(d.name() + ": " + d.kind() + " needs read handlers");
            if (!needsHandlers && !d.handlers().isEmpty()) problems.add(d.name() + ": " + d.kind() + " takes no handlers");
            for (String h : d.handlers()) {
                GatedDataScan.Endpoint e = scan.readEndpoints.get(h);
                if (e == null) problems.add(d.name() + ": no read handler " + h);
                else if ("GATE".equals(d.kind()) && !e.gated()) problems.add(d.name() + ": " + h + " is open, not a GATE surface");
                else if ("OPEN".equals(d.kind()) && e.gated()) problems.add(d.name() + ": " + h + " is gated, not an OPEN surface");
            }
            if (unservedOwners.stream().noneMatch(o -> d.owners().matcher(o).matches())) {
                problems.add(d.name() + ": matches no owner a tool calls unserved (stale row)");
            }
        }
        assertThat(problems).isEmpty();
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
            if (!Set.of("RULED", "SURFACE").contains(kind)) stale.add(tool + " (unknown kind " + kind + ")");
            if ("RULED".equals(kind) && declared(instance).isEmpty()) {
                stale.add(tool + " (ruled codes not declared)");
            }
            if ("SURFACE".equals(kind)) {
                for (String handler : surfaceHandlers(entry.getValue()[1])) {
                    if (!scan.readEndpoints.containsKey(handler)) stale.add(tool + " (no read handler " + handler + ")");
                }
                if (requirementsOf(tool, new ArrayList<>()).isEmpty()) stale.add(tool + " (reads no gated data: SURFACE not needed)");
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
                if (instance == null) continue;
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

    /** One thing a tool reads that only some callers may read: who the direct API admits to it. */
    record Requirement(String data, Set<String> codes, Set<String> roles) { }

    /** @return null when classified, otherwise what is wrong */
    private static String classify(String tool, ToolExecutor instance) {
        String[] registration = registrations.get(tool);
        Set<String> codes = declared(instance);
        if (codes.isEmpty()) {
            Set<String> mapped = ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(tool);
            if (mapped != null) codes = mapped;
        }
        // Every data method the tool calls is accounted for (strict, 2026-10-29 B2): served only by gated read
        // endpoints → a requirement; served by some open read endpoint → nothing; served by no read endpoint →
        // its owner's row in gated-data-domains.tsv decides (GATE → a requirement; OPEN / INTERNAL → nothing).
        List<String> unclassified = new ArrayList<>();
        List<Requirement> requirements = requirementsOf(tool, unclassified);
        if (!unclassified.isEmpty()) {
            return "no read endpoint serves " + unclassified + " and no row of gated-data-domains.tsv covers their owners";
        }
        if (registration != null && "RULED".equals(registration[0])) {
            return codes.isEmpty() ? "ruled but declares no codes" : null;
        }
        if (registration != null && "SURFACE".equals(registration[0])) {
            return surfaceMismatch(instance, codes, surfaceHandlers(registration[1]));
        }
        if (requirements.isEmpty()) return null;
        // Compared as roles under the default permission matrix (PermissionServiceImpl, no factory overrides):
        // the tool may admit no role that some direct read surface of its data refuses.
        Set<String> toolRoles = rolesAdmittedByTool(instance, codes);
        for (Requirement r : requirements) {
            Set<String> admitted = rolesAdmittedBy(r);
            Set<String> extra = new TreeSet<>(toolRoles);
            extra.removeAll(admitted);
            if (!extra.isEmpty()) {
                return (codes.isEmpty() && !roleGated(instance) ? "has no gate" : "admits " + extra)
                        + " but " + r.data() + " is served only to " + admitted
                        + " (codes " + r.codes() + (r.roles().isEmpty() ? "" : ", roles " + r.roles()) + ")";
            }
        }
        return toolRoles.isEmpty() ? "its gate admits nobody" : null;
    }

    /** Roles the tool's own gate admits: declared / mapped codes via the matrix, else its role gate, else everyone. */
    static Set<String> rolesAdmittedByTool(ToolExecutor instance, Set<String> codes) {
        // Mirrors ToolPermissionChain: the legacy role gate applies only when the tool declares no codes,
        // and then RBAC still applies the permission-map codes on top.
        Set<String> roles = Arrays.stream(FactoryUserRole.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        if (declared(instance).isEmpty() && roleGated(instance)) roles.retainAll(allowedRoles(instance));
        if (!codes.isEmpty()) roles.retainAll(rolesHoldingAny(codes));
        Set<String> requiredRoles = instance.getRequiredRoles();
        if (requiredRoles != null && !requiredRoles.isEmpty()) {
            roles = new TreeSet<>(roles);
            roles.removeIf(r -> !requiredRoles.contains(r) && !PLATFORM_ADMIN_ROLES.contains(r));
        }
        return roles;
    }

    /** RequireRoleInterceptor lets these through every @RequireRole; ToolRbacEnforcer does the same for getRequiredRoles. */
    static final Set<String> PLATFORM_ADMIN_ROLES = Set.of("super_admin", "platform_admin", "developer", "platform_super_admin");

    static Set<String> rolesAdmittedBy(Requirement r) {
        Set<String> roles = new TreeSet<>(r.roles());
        if (!r.roles().isEmpty()) roles.addAll(PLATFORM_ADMIN_ROLES);
        if (!r.codes().isEmpty()) roles.addAll(rolesHoldingAny(r.codes()));
        return roles;
    }

    private static final Map<Set<String>, Set<String>> MATRIX_CACHE = new HashMap<>();

    static Set<String> rolesHoldingAny(Set<String> codes) {
        return MATRIX_CACHE.computeIfAbsent(new TreeSet<>(codes), key -> {
            PermissionServiceImpl matrix = new PermissionServiceImpl();
            com.cretas.aims.testutil.PermissionRepositoryFixtures.installSuccessfulEmpty(matrix);
            Set<String> roles = new TreeSet<>();
            for (FactoryUserRole role : FactoryUserRole.values()) {
                User u = new User();
                // PermissionServiceImpl caches by user id: one id per role, or the first role's answer is reused.
                u.setId(100_000L + role.ordinal());
                u.setFactoryId(FACTORY);
                u.setRoleCode(role.name());
                u.setIsActive(true);
                if (matrix.hasAnyPermission(u, key.toArray(new String[0]))) roles.add(role.name());
            }
            return roles;
        });
    }

    /** Requirements of everything the tool reads; data that nothing classifies goes to {@code unclassified}. */
    static List<Requirement> requirementsOf(String tool, List<String> unclassified) {
        List<Requirement> requirements = new ArrayList<>();

        boolean write = ToolAccessModes.isWrite(scan.instances.get(tool));
        for (GatedDataScan.DataMethod data : scan.toolCalls.getOrDefault(tool, Set.of())) {
            List<GatedDataScan.Endpoint> endpoints = new ArrayList<>(scan.servedBy.getOrDefault(data, List.of()));
            List<GatedDataScan.Endpoint> writeEndpoints = scan.writeServedBy.getOrDefault(data, List.of());
            if (write && endpoints.isEmpty() && !writeEndpoints.isEmpty()) {
                // A pure write operation (only write endpoints call it): this sweep is about what tools read;
                // who may write is the write-confirmation / frontline-grant layer's question, not this one.
                continue;
            }
            // A write tool may read what a write endpoint reads on its way (look the order up, then record).
            if (write) endpoints.addAll(writeEndpoints);
            if (!endpoints.isEmpty()) {
                if (endpoints.stream().noneMatch(e -> !e.gated())) {
                    if (endpoints.stream().allMatch(e -> e.requireAll() && e.codes().size() > 1)) {
                        unclassified.add(data + " (served only by requireAll endpoints " + endpoints + ")");
                        continue;
                    }
                    requirements.add(requirementOf(data.toString(), endpoints));
                }
                continue;
            }
            Domain domain = domainOf(data.owner());
            if (domain == null) {
                unclassified.add(data.toString());
            } else if ("GATE".equals(domain.kind())) {
                requirements.add(requirementOf(data + " (domain " + domain.name() + ")", domain.endpoints()));
            }
        }
        return requirements;
    }

    private static Requirement requirementOf(String data, List<GatedDataScan.Endpoint> endpoints) {
        Set<String> codes = new TreeSet<>();
        Set<String> roles = new TreeSet<>();
        for (GatedDataScan.Endpoint e : endpoints) {
            // A requireAll endpoint admits only holders of all its codes: no single code of it admits anyone.
            if (!(e.requireAll() && e.codes().size() > 1)) codes.addAll(e.codes());
            roles.addAll(e.roles());
        }
        return new Requirement(data, codes, roles);
    }

    /** SURFACE reason column: {@code Controller#method[,Controller#method] — why}. */
    private static List<String> surfaceHandlers(String reason) {
        String head = reason.split(" ", 2)[0];
        return Arrays.asList(head.split(","));
    }

    /**
     * READ tool: its gate must equal the surface endpoints' gate (same codes, same roles, or none when they are
     * open), so it neither shows more nor hides what the App shows. WRITE tool: it may admit no role the surface
     * refuses (it carries its own write gate on top).
     */
    private static String surfaceMismatch(ToolExecutor instance, Set<String> codes, List<String> handlers) {
        if (ToolAccessModes.isWrite(instance)) {
            List<GatedDataScan.Endpoint> endpoints = new ArrayList<>();
            for (String handler : handlers) {
                GatedDataScan.Endpoint e = scan.readEndpoints.get(handler);
                if (e == null) return "no read handler " + handler;
                endpoints.add(e);
            }
            if (endpoints.stream().anyMatch(e -> !e.gated())) return null;
            Set<String> admitted = rolesAdmittedBy(requirementOf(String.join(",", handlers), endpoints));
            Set<String> extra = new TreeSet<>(rolesAdmittedByTool(instance, codes));
            extra.removeAll(admitted);
            return extra.isEmpty() ? null : "admits " + extra + " but " + handlers + " admit only " + admitted;
        }
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

    static Set<String> declared(ToolExecutor tool) {
        Set<String> codes = tool == null ? null : tool.getRequiredPermissions();
        return codes == null ? Set.of() : codes;
    }

    static boolean roleGated(ToolExecutor tool) {
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
