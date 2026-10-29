package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S6 acceptance (ERP-S6-02-SKILL-ROLE-BYPASS-001): a production manager ran the monthly-financial-close
 * Skill and read the balance sheet, while {@code GET finance/report/balance-sheet} is 403 for the same
 * person. The tools call services directly, so the controller's {@code @RequirePermission} never ran
 * on the AI / Skill path.
 *
 * <p>Rule: an AI or Skill path never returns more than the caller can get from the direct API. Each
 * tool that reads data behind a permission-gated controller carries exactly the codes of the HTTP
 * method that serves the same data ({@link ToolRbacEnforcer#TOOL_PERMISSION_MAP}). The codes are
 * read from the controller annotations here, never copied by hand. A sweep then fails for any tool
 * (or registered Skill's tool) that injects a service or repository used only by gated controllers
 * yet carries no permission and is not registered with a reason.</p>
 */
@DisplayName("受控数据: AI / Skill 路径的权限与直接接口逐字相同")
public class GatedDataToolAiPathPermissionTest {

    private static final String FACTORY = "F006";
    private static final long PRODUCTION_MANAGER = 9501L;
    private static final long FINANCE_MANAGER = 9502L;

    /** tool → HTTP counterpart serving the same service call (controller class, method name). */
    static final Map<String, String[]> TOOL_TO_HTTP = new LinkedHashMap<>();
    static {
        String fr = "com.cretas.aims.controller.finance.FinanceReportController";
        String rc = "com.cretas.aims.controller.ReportController";
        pair("balance_sheet_query", fr, "balanceSheet");
        pair("income_statement_query", fr, "incomeStatement");
        pair("cashflow_statement_query", fr, "cashFlow");
        pair("period_status_query", "com.cretas.aims.controller.finance.AccountingPeriodController", "status");
        pair("account_query", "com.cretas.aims.controller.finance.AccountController", "list");
        pair("account_tree_lookup", "com.cretas.aims.controller.finance.AccountController", "tree");
        pair("accounts_receivable_aging", "com.cretas.aims.controller.finance.ArApController", "getAgingAnalysis");
        pair("sales_record_payment", "com.cretas.aims.controller.finance.ArApController", "recordArPayment");
        for (String tool : List.of("finance_dupont", "finance_liquidity", "finance_roa", "finance_roe",
                "finance_solvency", "finance_stats", "report_finance", "report_finance_ratio",
                "report_payment_status", "payment_status")) {
            pair(tool, rc, "getFinanceReport");
        }
        pair("finance_invoice_status", "com.cretas.aims.controller.finance.InvoiceController", "detail");
        pair("finance_payment_record", "com.cretas.aims.controller.finance.PaymentRecordController", "recordPayment");
        pair("wage_cost_summary", "com.cretas.aims.controller.WagePolicyController", "listCalculations");
        pair("opportunity_funnel_stats", "com.cretas.aims.controller.SalesOpportunityController", "funnelStats");
        pair("commission_pending_total", "com.cretas.aims.controller.CommissionController", "list");
        pair("report_dashboard_overview", rc, "getDashboardOverview");
        pair("report_sales", rc, "getDashboardOverview");
        pair("sales_report", rc, "getDashboardOverview");
        pair("report_workshop_daily", rc, "getProductionDashboard");
        pair("report_trends", rc, "getPeriodComparisonReport");
        pair("report_oee", rc, "getOeeReport");
        pair("report_efficiency", rc, "getEfficiencyAnalysisReport");
        pair("report_benefit_overview", rc, "getEfficiencyAnalysisReport");
        pair("report_kpi", rc, "getKPIMetrics");
        pair("report_anomaly", rc, "getAnomalyReport");
        pair("report_cost_variance", rc, "getCostVarianceReport");
        pair("report_inventory", rc, "getInventoryReport");
        pair("report_mrp_calculation", rc, "getInventoryReport");
        pair("ecn_create", "com.cretas.aims.controller.EcnController", "create");
        String ro = "com.cretas.aims.controller.inventory.ReturnOrderController";
        pair("return_order_list", ro, "listReturnOrders");
        pair("return_order_detail", ro, "getReturnOrder");
        pair("return_order_statistics", ro, "getStatistics");
        pair("return_order_approve", ro, "approveReturnOrder");
        pair("batch_consumption_query", "com.cretas.aims.controller.MaterialConsumptionController",
                "getBatchConsumptionSummary");
    }

    private static void pair(String tool, String controller, String method) {
        TOOL_TO_HTTP.put(tool, new String[]{controller, method});
    }

    /**
     * Tools that inject a gated-only service but are deliberately not gated, each with the reason.
     * An entry here is a registration, not an exemption from review.
     */
    static final Map<String, String> REGISTERED_NOT_GATED = new LinkedHashMap<>();
    static {
        String noCounterpart = "调用的 ReportService 方法没有任何 HTTP 方法暴露 (getProductionReport / getQualityReport /"
                + " getTrendAnalysisReport), 没有可对齐的直接接口; 内容是生产 / 质检运营数据, 不含金额";
        REGISTERED_NOT_GATED.put("report_production", noCounterpart);
        REGISTERED_NOT_GATED.put("report_quality", noCounterpart);
        REGISTERED_NOT_GATED.put("report_ai_quality", noCounterpart);
        REGISTERED_NOT_GATED.put("report_production_weekly_comparison", noCounterpart);
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
        // finance_manager holds finance:read(_write); production_manager holds only production codes.
        when(permissions.hasAnyPermission(any(), any(String[].class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            Object[] args = call.getArguments();
            Set<String> asked = new HashSet<>();
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof String code) asked.add(code);
                if (args[i] instanceof String[] codes) asked.addAll(Arrays.asList(codes));
            }
            Set<String> held = user.getId() == FINANCE_MANAGER
                    ? Set.of("finance:read", "finance:read_write")
                    : Set.of("production:read", "production:read_write");
            return asked.stream().anyMatch(held::contains);
        });
    }

    // ==================== AI path: mirrored codes ====================

    @ParameterizedTest(name = "{0} 的权限码 = {1}#{2} 的 @RequirePermission")
    @MethodSource("pairs")
    void aiPathCodesEqualTheDirectApiCodes(String tool, String controller, String method) throws Exception {
        assertThat(ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(tool))
                .as("%s must carry exactly the codes of %s#%s", tool, controller, method)
                .isEqualTo(effectiveCodes(Class.forName(controller), method));
    }

    static Stream<Arguments> pairs() {
        return TOOL_TO_HTTP.entrySet().stream()
                .map(e -> Arguments.of(e.getKey(), e.getValue()[0], e.getValue()[1]));
    }

    @Test
    @DisplayName("生产经理经 AI 查资产负债表 → 拒绝; 财务经理 → 放行 (与直接接口一致)")
    void productionManagerIsDeniedAndFinanceManagerAllowedOnTheAiPath() {
        for (String tool : List.of("balance_sheet_query", "income_statement_query", "cashflow_statement_query",
                "period_status_query", "accounts_receivable_aging")) {
            assertThat(enforcer.check(named(tool), ctx(PRODUCTION_MANAGER, "production_manager")).isAllowed())
                    .as("production_manager on %s", tool).isFalse();
            assertThat(enforcer.check(named(tool), ctx(FINANCE_MANAGER, "finance_manager")).isAllowed())
                    .as("finance_manager on %s", tool).isTrue();
        }
        // The intent 「查看本月资产负债表」 (BALANCE_SHEET_QUERY → balance_sheet_query) is dispatched through
        // ToolPermissionChain, the same chain checked here.
        com.cretas.aims.service.intent.FrontlineAiWriteGrantService frontline =
                mock(com.cretas.aims.service.intent.FrontlineAiWriteGrantService.class);
        when(frontline.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        ToolPermissionChain chain = new ToolPermissionChain(enforcer, frontline);
        assertThat(chain.denyReason(named("balance_sheet_query"), FACTORY, "production_manager", null,
                ctx(PRODUCTION_MANAGER, "production_manager"))).isPresent();
        assertThat(chain.denyReason(named("balance_sheet_query"), FACTORY, "finance_manager", null,
                ctx(FINANCE_MANAGER, "finance_manager"))).isEmpty();
        // Control: an ungated operational read still passes for the production manager.
        assertThat(enforcer.check(named("report_production"), ctx(PRODUCTION_MANAGER, "production_manager"))
                .isAllowed()).isTrue();
    }

    // ==================== Skill path: monthly-financial-close ====================

    @Test
    @DisplayName("生产经理运行月度财务复盘 Skill → 整个 Skill 按无权限拒绝, 一个工具都不执行; 财务经理只跑他能直接看的部分并被告知缺了什么")
    void theFinanceCloseSkillIsRefusedBeforeAnyToolRunsForAProductionManager() throws Exception {
        SkillRegistryImpl registry = defaultSkills();
        SkillDefinition close = registry.getSkill("monthly-financial-close").orElseThrow();

        ToolRegistry tools = mock(ToolRegistry.class);
        List<String> executed = new ArrayList<>();
        for (String toolName : close.getTools()) {
            ToolExecutor tool = recording(toolName, executed);
            when(tools.getExecutor(toolName)).thenReturn(Optional.of(tool));
            when(tools.hasExecutor(toolName)).thenReturn(true);
            when(tools.isToolEnabledForFactory(any(), any())).thenReturn(true);
        }
        DashScopeClient llm = mock(DashScopeClient.class);
        when(llm.chat(any(), any())).thenReturn("{\"tools_to_execute\":[]}");
        SkillExecutorImpl executor = new SkillExecutorImpl(tools, llm, new ObjectMapper());
        com.cretas.aims.service.intent.FrontlineAiWriteGrantService frontline =
                mock(com.cretas.aims.service.intent.FrontlineAiWriteGrantService.class);
        when(frontline.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(executor, "toolPermissionChain", new ToolPermissionChain(enforcer, frontline));
        ReflectionTestUtils.setField(executor, "writeGuardService", mock(WriteGuardService.class));
        ReflectionTestUtils.setField(executor, "maxToolsPerSkill", 8);

        SkillResult refused = executor.execute(close, context(PRODUCTION_MANAGER, "production_manager"));
        assertThat(refused.isSuccess()).isFalse();
        assertThat(refused.isPermissionDenied()).isTrue();
        assertThat(executed).isEmpty();
        verifyNoLlmCall(llm);

        SkillResult allowed = executor.execute(close, context(FINANCE_MANAGER, "finance_manager"));
        assertThat(allowed.isSuccess()).as(allowed.getMessage()).isTrue();
        assertThat(allowed.isPermissionDenied()).isFalse();
        // finance_manager holds finance codes but not hr / sales:view (as in the real matrix): the wage,
        // funnel and commission parts are left out and named, never run.
        assertThat(executed).contains("balance_sheet_query", "income_statement_query", "cashflow_statement_query",
                "period_status_query", "accounts_receivable_aging");
        assertThat(executed).doesNotContain("wage_cost_summary", "opportunity_funnel_stats", "commission_pending_total");
        assertThat(allowed.getMessage()).contains("3 项").contains("wage_cost_summary");
    }

    @Test
    @DisplayName("Skill 入口把无权限拒绝映射成 403 (与直接接口同一个状态码)")
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

    // ==================== Sweep ====================

    @Test
    @DisplayName("🔴 完备性: 注入了「只被带门 controller 使用」的 service/repository 的工具, 要么挂码要么登记理由")
    void everyToolTouchingGatedDataCarriesAPermissionOrIsRegistered() throws Exception {
        Set<Class<?>> gatedOnly = gatedOnlyTypes();
        assertThat(gatedOnly).as("至少要量出财务报表服务, 否则判据坏了")
                .extracting(Class::getSimpleName).contains("BalanceSheetService", "IncomeStatementService");

        Map<String, Set<String>> toolTypes = toolsTouching(gatedOnly);
        assertThat(toolTypes).as("至少要扫到月度财务复盘用的工具, 否则扫描坏了")
                .containsKeys("balance_sheet_query", "income_statement_query", "cashflow_statement_query");

        List<String> unaccounted = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : toolTypes.entrySet()) {
            if (resolvedCodes(entry.getKey()).isEmpty() && !REGISTERED_NOT_GATED.containsKey(entry.getKey())) {
                unaccounted.add(entry.getKey() + " " + entry.getValue());
            }
        }
        assertThat(unaccounted)
                .as("这些工具读的是只被带门 controller 使用的数据, 却没有权限码也没有登记理由")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 完备性: 内置 Skill 用到的读受控数据的工具全部挂了码 (或已登记)")
    void everyRegisteredSkillToolTouchingGatedDataCarriesAPermission() throws Exception {
        SkillRegistryImpl registry = defaultSkills();
        Map<String, Set<String>> gatedTools = toolsTouching(gatedOnlyTypes());
        List<SkillDefinition> skills = registry.getAllSkills();
        assertThat(skills).as("内置 Skill 一个都没加载到 ⇒ 这条闸在守空气").hasSizeGreaterThan(10);

        List<String> unaccounted = new ArrayList<>();
        for (SkillDefinition skill : skills) {
            if (skill.getTools() == null) continue;
            for (String tool : skill.getTools()) {
                if (gatedTools.containsKey(tool) && resolvedCodes(tool).isEmpty()
                        && !REGISTERED_NOT_GATED.containsKey(tool)) {
                    unaccounted.add(skill.getName() + " → " + tool);
                }
            }
        }
        assertThat(unaccounted).isEmpty();
        assertThat(registry.getSkill("monthly-financial-close").orElseThrow().getTools())
                .allSatisfy(tool -> assertThat(resolvedCodes(tool)).as(tool).isNotEmpty());
    }

    @Test
    void everyRegisteredExemptionCarriesAReasonAndStillTouchesGatedData() throws Exception {
        Map<String, Set<String>> toolTypes = toolsTouching(gatedOnlyTypes());
        assertThat(REGISTERED_NOT_GATED.values()).allSatisfy(reason -> assertThat(reason).hasSizeGreaterThan(20));
        assertThat(toolTypes.keySet()).as("stale registration").containsAll(REGISTERED_NOT_GATED.keySet());
    }

    // ==================== helpers ====================

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

    /** Types injected only by controllers that carry a class-level {@code @RequirePermission}. */
    private static Set<Class<?>> gatedOnlyTypes() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<Class<?>, Boolean> allGated = new HashMap<>();
        int controllers = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims.controller")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            controllers++;
            boolean gated = c.isAnnotationPresent(RequirePermission.class);
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getType().getSimpleName();
                if (!(name.endsWith("Service") || name.endsWith("Repository"))) continue;
                allGated.merge(f.getType(), gated, Boolean::logicalAnd);
            }
        }
        assertThat(controllers).as("no controllers scanned").isGreaterThan(100);
        return allGated.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** tool name → the gated-only types it injects. */
    private static Map<String, Set<String>> toolsTouching(Set<Class<?>> gatedOnly) throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
        Map<String, Set<String>> result = new LinkedHashMap<>();
        List<String> uninstantiable = new ArrayList<>();
        int tools = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims.ai.tool")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (Modifier.isAbstract(c.getModifiers()) || c.isInterface()) continue;
            tools++;
            Set<String> touched = new TreeSet<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (gatedOnly.contains(f.getType())) touched.add(f.getType().getSimpleName());
                }
            }
            if (touched.isEmpty()) continue;
            try {
                result.put(((ToolExecutor) c.getDeclaredConstructor().newInstance()).getToolName(), touched);
            } catch (ReflectiveOperationException e) {
                uninstantiable.add(c.getSimpleName());
            }
        }
        assertThat(tools).as("no tools scanned").isGreaterThan(100);
        assertThat(uninstantiable).as("tools whose name could not be read: this sweep is blind to them").isEmpty();
        return result;
    }

    private static Set<String> resolvedCodes(String toolName) {
        Set<String> mapped = ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(toolName);
        if (mapped != null && !mapped.isEmpty()) return mapped;
        return declaredCodes(toolName);
    }

    private static final Map<String, Set<String>> DECLARED = new HashMap<>();

    private static Set<String> declaredCodes(String toolName) {
        if (DECLARED.isEmpty()) {
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));
            for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims.ai.tool")) {
                try {
                    Class<?> c = Class.forName(bd.getBeanClassName());
                    if (Modifier.isAbstract(c.getModifiers()) || c.isInterface()) continue;
                    ToolExecutor tool = (ToolExecutor) c.getDeclaredConstructor().newInstance();
                    Set<String> codes = tool.getRequiredPermissions();
                    DECLARED.put(tool.getToolName(), codes == null ? Set.of() : codes);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // uninstantiable tools are reported by toolsTouching
                }
            }
        }
        return DECLARED.getOrDefault(toolName, Set.of());
    }

    private static Set<String> effectiveCodes(Class<?> controller, String methodName) {
        List<Method> found = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName)).toList();
        assertThat(found).as("%s#%s must be exactly one method", controller.getSimpleName(), methodName).hasSize(1);
        RequirePermission onMethod = found.get(0).getAnnotation(RequirePermission.class);
        RequirePermission onClass = controller.getAnnotation(RequirePermission.class);
        RequirePermission effective = onMethod != null ? onMethod : onClass;
        assertThat(effective).as("%s#%s has no @RequirePermission", controller.getSimpleName(), methodName)
                .isNotNull();
        return Set.of(effective.value());
    }

    private static ToolExecutor named(String name) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
        };
    }

    private static ToolExecutor recording(String name, List<String> executed) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public AccessMode getAccessMode() { return AccessMode.READ; }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) {
                executed.add(name);
                return "{\"success\":true}";
            }
        };
    }

    private static void verifyNoLlmCall(DashScopeClient llm) {
        org.mockito.Mockito.verifyNoInteractions(llm);
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
