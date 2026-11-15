package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.CustomerListTool;
import com.cretas.aims.ai.tool.impl.workdesk.CustomerPriorityQueryTool;
import com.cretas.aims.ai.tool.impl.workdesk.OpportunityStageAlertTool;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INTENT-DISTILL-20261111 红线: 澄清候选与执行期工具判定是<b>同一份</b>。
 *
 * <p>prod ai-live-probe {@code clarify_then_pick_1} (2026-11-14 23:47 / 11-15 13:27 两轮红): 只读评测号
 * (viewer) 说「这个客户的量给我弄一下」, 候选 = 商机超 SLA 警告 / 客户优先级排序 / 客户列表; 回「1」⇒
 * PERMISSION_DENIED。前两个工具自带 {@code getRequiredRoles()} (销售管理类角色), 候选只查意图级权限,
 * 执行期 {@link ToolRbacEnforcer} 才查工具级 —— 两份判定。识别排序换一下 (客户列表排第一) 就绿, 所以不稳定红。
 *
 * <p>这里用<b>真</b>工具类 (它们自己声明的角色/权限码)、真 {@link ToolRbacEnforcer} + 真 {@link ToolRbacGuard}
 * + 真 {@link ToolPermissionChain}; 只桩用户表与权限矩阵 (PermissionService)。
 */
@DisplayName("澄清候选 = 执行期工具判定: 执行期会拒的工具不出按钮, 会放行的照出")
class ClarificationToolRbacParityContractTest {

    private static final String FACTORY = "F006";
    private static final String OPP = "OPPORTUNITY_STAGE_ALERT";
    private static final String PRIORITY = "CUSTOMER_PRIORITY_QUERY";
    private static final String CUSTOMERS = "CUSTOMER_LIST";
    private static final String NO_TOOL = "FAQ_NO_TOOL";
    private static final String LEGACY = "LEGACY_ROLE_STRING";
    private static final String ALSO = "ALSO_MODULE_GATED";
    /** tool_name=NULL, 执行期走显式 Skill daily-customer-followup (含商机预警 / 客户优先级两个销售管理类工具)。 */
    private static final String FOLLOWUP = "DAILY_CUSTOMER_FOLLOWUP";

    /** 角色 → (userId, 该角色在矩阵里拥有的权限码)。viewer 与 prod 读数一致: 客户列表能跑 (有 sales:read)。 */
    private static final Map<String, Long> USER_OF = new LinkedHashMap<>();
    private static final Map<String, Set<String>> GRANTED = Map.of(
            "viewer", Set.of("sales:read", "finance:read"),
            "sales_manager", Set.of("sales:read_write", "sales:read"),
            "quality_manager", Set.of("quality:read_write"),
            "factory_super_admin", Set.of("sales:read_write", "finance:read_write", "analytics:read"),
            "super_admin", Set.of("sales:read_write", "finance:read_write", "analytics:read"));

    static {
        long id = 100;
        for (String role : List.of("viewer", "sales_manager", "quality_manager", "factory_super_admin", "super_admin",
                "warehouse_manager")) {
            USER_OF.put(role, id++);
        }
    }

    private IntentConfigManagementService configService;
    private ToolRegistry toolRegistry;
    private ToolRbacEnforcer enforcer;
    private ToolPermissionChain chain;
    private ClarificationCandidateService service;
    private final Map<String, ToolExecutor> toolByIntent = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        USER_OF.forEach((role, id) -> {
            User u = new User();
            u.setId(id);
            u.setRoleCode(role);
            when(users.findById(id)).thenReturn(Optional.of(u));
        });
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            Object[] raw = Arrays.copyOfRange(inv.getArguments(), 1, inv.getArguments().length);
            List<String> codes = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof String[] arr) codes.addAll(Arrays.asList(arr));
                else if (o instanceof String s) codes.add(s);
            }
            Set<String> granted = GRANTED.getOrDefault(u.getRoleCode(), Set.of());
            return codes.stream().anyMatch(granted::contains);
        });
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        FrontlineAiWriteGrantService grants = mock(FrontlineAiWriteGrantService.class);
        when(grants.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        chain = new ToolPermissionChain(enforcer, grants);

        configService = mock(IntentConfigManagementService.class);
        toolRegistry = mock(ToolRegistry.class);
        // 默认本厂工具都开着; M1 用例单独关一个
        when(toolRegistry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        // 意图级 required_roles 为空 = 放行: 差异只可能来自工具级门 (prod 上这三个意图正是如此)
        when(configService.hasPermission(eq(FACTORY), anyString(), anyString())).thenReturn(true);

        bind(OPP, "商机超 SLA 警告", new OpportunityStageAlertTool());
        bind(PRIORITY, "客户优先级排序", new CustomerPriorityQueryTool());
        bind(CUSTOMERS, "客户列表", new CustomerListTool());
        bind(LEGACY, "旧角色串工具", legacyRoleTool());
        bind(ALSO, "且模块码工具", alsoGatedTool());
        bind(NO_TOOL, "常见问题", null);
        bind(FOLLOWUP, "今日客户跟进", null);

        service = new ClarificationCandidateService(configService, mock(IntentPermissionGate.class),
                accessModeFilter, new WriteGuardService(), grants, toolRegistry, chain);

        // 真 Skill 栈: 内置 Skill 全量 (含 daily-customer-followup) + 真执行器的权限预检, 同一条 chain
        SkillRegistryImpl skills = new SkillRegistryImpl(mock(SmartBiSkillRepository.class), new ObjectMapper());
        ReflectionTestUtils.setField(skills, "loadDefaults", true);
        ReflectionTestUtils.setField(skills, "skillsDirectory", "classpath:skills/");
        ReflectionTestUtils.setField(skills, "defaultMinMatchScore", 0.1);
        skills.loadSkills();
        skillExecutor = new SkillExecutorImpl(toolRegistry, mock(com.cretas.aims.ai.client.DashScopeClient.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(skillExecutor, "toolPermissionChain", chain);
        ReflectionTestUtils.setField(service, "skillRegistry", skills);
        ReflectionTestUtils.setField(service, "skillExecutor", skillExecutor);
        followupSkill = skills.getSkillForFactory(FACTORY, "daily-customer-followup");
    }

    private SkillExecutorImpl skillExecutor;
    private com.cretas.aims.dto.skill.SkillDefinition followupSkill;

    private void bind(String code, String name, ToolExecutor tool) {
        String toolName = tool == null ? null : tool.getToolName();
        AIIntentConfig config = AIIntentConfig.builder().intentCode(code).intentName(name).businessType("COMMON")
                .intentCategory("QUERY").sensitivityLevel("LOW").toolName(toolName).build();
        config.setIsActive(true);
        when(configService.getIntentByCode(FACTORY, code)).thenReturn(Optional.of(config));
        if (tool != null) {
            when(toolRegistry.getExecutor(toolName)).thenReturn(Optional.of(tool));
            toolByIntent.put(code, tool);
        }
    }

    private static ToolExecutor legacyRoleTool() {
        ToolExecutor t = mock(ToolExecutor.class);
        when(t.getToolName()).thenReturn("legacy_role_tool");
        when(t.requiresPermission()).thenReturn(true);
        when(t.getRequiredPermissions()).thenReturn(Set.of());
        when(t.getRequiredRoles()).thenReturn(Set.of());
        when(t.getAlsoRequiredPermissions()).thenReturn(Set.of());
        when(t.hasPermission(anyString())).thenAnswer(inv -> "quality_manager".equals(inv.getArgument(0)));
        return t;
    }

    private static ToolExecutor alsoGatedTool() {
        ToolExecutor t = mock(ToolExecutor.class);
        when(t.getToolName()).thenReturn("also_gated_tool");
        when(t.getRequiredPermissions()).thenReturn(Set.of("sales:read", "sales:read_write"));
        when(t.getAlsoRequiredPermissions()).thenReturn(Set.of("analytics:read"));
        when(t.getRequiredRoles()).thenReturn(Set.of());
        return t;
    }

    private List<String> offered(String role, String... codes) {
        IntentMatchResult match = IntentMatchResult.builder().topCandidates(Arrays.stream(codes)
                .map(c -> IntentMatchResult.CandidateIntent.builder().intentCode(c).intentName("untrusted " + c)
                        .confidence(0.4).build()).toList()).build();
        return service.buildCandidateActions(match, FACTORY, USER_OF.get(role), role, "READ").stream()
                .filter(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .map(a -> String.valueOf(a.getParameters().get("intentCode")))
                .toList();
    }

    /** 执行期 SITE B (ToolDispatchService#executeWithTool) 的两道工具门, 按它自己的调用方式独立再问一遍。 */
    private boolean executionAllows(String role, ToolExecutor tool) {
        if (ToolPermissionChain.legacyRoleDenied(tool, role)) {
            return false;
        }
        Map<String, Object> ctx = new java.util.HashMap<>();
        TrustedExecutionContext.enforcePrincipal(ctx, FACTORY, USER_OF.get(role), role);
        return enforcer.check(tool, ctx).isAllowed();
    }

    @Test
    @DisplayName("回归 (prod clarify_then_pick_1): viewer 的候选里没有执行期会拒的「商机超 SLA / 客户优先级」, 客户列表照出")
    void viewerIsNotOfferedToolsExecutionWouldDeny() {
        // 执行期确实拒这两个、放行客户列表 —— 先证明前提, 否则下面的「不出现」没有意义
        assertThat(executionAllows("viewer", toolByIntent.get(OPP))).isFalse();
        assertThat(executionAllows("viewer", toolByIntent.get(PRIORITY))).isFalse();
        assertThat(executionAllows("viewer", toolByIntent.get(CUSTOMERS))).isTrue();

        assertThat(offered("viewer", OPP, PRIORITY, CUSTOMERS)).containsExactly(CUSTOMERS);
    }

    @Test
    @DisplayName("阴性对照: 有权限的角色 (sales_manager / 平台管理员) 同一组候选三个都出")
    void permittedRolesStillSeeAllThree() {
        assertThat(offered("sales_manager", OPP, PRIORITY, CUSTOMERS)).containsExactly(OPP, PRIORITY, CUSTOMERS);
        assertThat(offered("super_admin", OPP, PRIORITY, CUSTOMERS)).containsExactly(OPP, PRIORITY, CUSTOMERS);
    }

    @Test
    @DisplayName("一致性: 角色 × 工具 (工具角色 / 内置映射 / 旧角色串 / 且模块码) 每一格候选与执行期判定相同")
    void candidateDecisionEqualsExecutionDecisionForEveryRoleAndGateKind() {
        int allowedCells = 0;
        int deniedCells = 0;
        for (String role : USER_OF.keySet()) {
            for (Map.Entry<String, ToolExecutor> e : toolByIntent.entrySet()) {
                boolean exec = executionAllows(role, e.getValue());
                boolean shown = offered(role, e.getKey()).contains(e.getKey());
                assertThat(shown).as("role=%s intent=%s execAllows=%s", role, e.getKey(), exec).isEqualTo(exec);
                if (exec) allowedCells++; else deniedCells++;
            }
        }
        // 矩阵两边都要有格子, 否则「相等」可能是全 true / 全 false 的恒真式
        assertThat(allowedCells).isGreaterThanOrEqualTo(5);
        assertThat(deniedCells).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("没绑定工具的意图不受工具门影响 (执行期也没有工具门)")
    void intentWithoutToolIsUnaffected() {
        assertThat(offered("viewer", NO_TOOL)).containsExactly(NO_TOOL);
    }

    @Test
    @DisplayName("裸构造 (没有判定链) + 意图绑定了已注册工具 ⇒ fail-closed 不出按钮")
    void missingChainFailsClosedForRegisteredTool() {
        FrontlineAiWriteGrantService grants = mock(FrontlineAiWriteGrantService.class);
        when(grants.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
        ClarificationCandidateService bare = new ClarificationCandidateService(configService,
                mock(IntentPermissionGate.class), accessModeFilter, new WriteGuardService(), grants, toolRegistry);
        IntentMatchResult match = IntentMatchResult.builder().topCandidates(List.of(
                IntentMatchResult.CandidateIntent.builder().intentCode(CUSTOMERS).confidence(0.4).build(),
                IntentMatchResult.CandidateIntent.builder().intentCode(NO_TOOL).confidence(0.4).build())).build();
        List<String> shown = bare.buildCandidateActions(match, FACTORY, USER_OF.get("sales_manager"), "sales_manager", "READ")
                .stream().filter(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .map(a -> String.valueOf(a.getParameters().get("intentCode"))).toList();
        assertThat(shown).containsExactly(NO_TOOL);
    }

    @Test
    @DisplayName("M2 回归: 没绑工具、靠 Skill 执行的「今日客户跟进」—— 执行期 Skill 预检会拒的 viewer 不出, sales_manager 照出")
    void skillBackedIntentFollowsSkillPermissionPreflight() {
        assertThat(followupSkill).as("内置 Skill daily-customer-followup 必须存在, 否则本条没有量到东西").isNotNull();
        assertThat(followupSkill.getTools()).contains("opportunity_stage_alert", "customer_priority_query");
        // 前提: 执行期 (SkillExecutorImpl 第 2a 步) 对 viewer 会整个拒, 对 sales_manager 放行
        assertThat(skillExecutor.permissionRefusal(followupSkill, skillCtx("viewer"))).isPresent();
        assertThat(skillExecutor.permissionRefusal(followupSkill, skillCtx("sales_manager"))).isEmpty();

        assertThat(offered("viewer", FOLLOWUP, CUSTOMERS)).containsExactly(CUSTOMERS);
        assertThat(offered("sales_manager", FOLLOWUP, CUSTOMERS)).containsExactly(FOLLOWUP, CUSTOMERS);
    }

    @Test
    @DisplayName("Skill 存在但预检器缺失 ⇒ fail-closed 不出; 没有对应 Skill 的无工具意图不受影响")
    void skillBackedIntentFailsClosedWithoutExecutor() {
        ReflectionTestUtils.setField(service, "skillExecutor", null);
        assertThat(offered("sales_manager", FOLLOWUP, NO_TOOL)).containsExactly(NO_TOOL);
    }

    // ───────────── #1747 遗留 (INTENT-DISTILL-20261111 第③步 PR-1) ─────────────

    @Test
    @DisplayName("M1 回归: 工厂在功能配置里关掉的工具, 执行期回 TOOL_DISABLED ⇒ 候选不出; 开着时照出")
    void factoryDisabledToolIsNotOffered() {
        ToolExecutor customers = toolByIntent.get(CUSTOMERS);
        when(toolRegistry.isToolEnabledForFactory(FACTORY, customers.getToolName())).thenReturn(false);
        // 前提: 执行期同一个判定确实拒 (ToolDispatchService#executeWithToolInScope 调的就是它)
        assertThat(ToolExecutionAdmission.refusal(toolRegistry, customers, intentConfig(CUSTOMERS), FACTORY,
                USER_OF.get("sales_manager"))).contains(ToolExecutionAdmission.Refusal.TOOL_DISABLED);
        assertThat(offered("sales_manager", OPP, CUSTOMERS)).containsExactly(OPP);

        when(toolRegistry.isToolEnabledForFactory(FACTORY, customers.getToolName())).thenReturn(true);
        assertThat(offered("sales_manager", OPP, CUSTOMERS)).containsExactly(OPP, CUSTOMERS);
    }

    @Test
    @DisplayName("L1 回归: 供应商比价只给绑定本厂的工厂用户 —— 平台管理员 / 别厂用户 / 无身份都不出, 本厂用户照出")
    void supplierComparisonFollowsExecutionIdentityGate() {
        ToolExecutor supplier = mock(ToolExecutor.class);
        when(supplier.getToolName()).thenReturn(SupplierComparisonReadIntent.TOOL);
        when(supplier.getRequiredPermissions()).thenReturn(Set.of());
        when(supplier.getRequiredRoles()).thenReturn(Set.of());
        when(supplier.getAlsoRequiredPermissions()).thenReturn(Set.of());
        bind(SupplierComparisonReadIntent.CODE, "供应商比价", supplier);
        String role = "factory_super_admin";
        Long uid = USER_OF.get(role);
        // 前提: 角色侧放行, 差异只可能来自身份门
        assertThat(executionAllows(role, supplier)).isTrue();

        assertThat(offered(role, SupplierComparisonReadIntent.CODE, CUSTOMERS))
                .as("没有可信身份").containsExactly(CUSTOMERS);
        try (var ignored = com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(
                new com.cretas.aims.security.AuthenticatedActor(
                        com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN, uid, null, "super_admin"))) {
            assertThat(offered(role, SupplierComparisonReadIntent.CODE, CUSTOMERS))
                    .as("平台管理员替本厂对话").containsExactly(CUSTOMERS);
        }
        try (var ignored = com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(
                new com.cretas.aims.security.AuthenticatedActor(
                        com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, uid, "F999", role))) {
            assertThat(offered(role, SupplierComparisonReadIntent.CODE, CUSTOMERS))
                    .as("别厂的工厂用户").containsExactly(CUSTOMERS);
        }
        try (var ignored = com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(
                new com.cretas.aims.security.AuthenticatedActor(
                        com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, uid, FACTORY, role))) {
            assertThat(ToolExecutionAdmission.refusal(toolRegistry, supplier,
                    intentConfig(SupplierComparisonReadIntent.CODE), FACTORY, uid)).isEmpty();
            assertThat(offered(role, SupplierComparisonReadIntent.CODE, CUSTOMERS))
                    .as("阴性对照: 绑定本厂的工厂用户").containsExactly(SupplierComparisonReadIntent.CODE, CUSTOMERS);
        }
    }

    @Test
    @DisplayName("L2 回归: 意图配了工具名但工具没注册 (执行期只会回「Tool 未注册」) ⇒ 不出按钮")
    void configuredButUnregisteredToolIsNotOffered() {
        AIIntentConfig ghost = AIIntentConfig.builder().intentCode("GHOST_TOOL_INTENT").intentName("幽灵工具")
                .businessType("COMMON").intentCategory("QUERY").sensitivityLevel("LOW").toolName("ghost_tool").build();
        ghost.setIsActive(true);
        when(configService.getIntentByCode(FACTORY, "GHOST_TOOL_INTENT")).thenReturn(Optional.of(ghost));
        assertThat(toolRegistry.getExecutor("ghost_tool")).isEmpty();
        assertThat(offered("super_admin", "GHOST_TOOL_INTENT", CUSTOMERS)).containsExactly(CUSTOMERS);
    }

    @Test
    @DisplayName("L-a 回归: 库存分析执行期是固定工作流经网关跑 APPROVED_TOOLS; 预检按网关准入判, 库里改过的定义不算数")
    void inventoryAnalysisPreflightFollowsWorkflowGatewayAdmission() {
        List<String> approved = com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflow.APPROVED_TOOLS;
        for (String name : approved) {
            ToolExecutor t = mock(ToolExecutor.class);
            when(t.getToolName()).thenReturn(name);
            when(t.getVersion()).thenReturn("1.0.0");
            when(toolRegistry.getExecutor(name)).thenReturn(Optional.of(t));
        }
        // 网关: 仓管能跑这三个工具, viewer 不能 (工作流执行期正是按网关准入一个个跑, 第一个被拒就停)
        var principals = mock(com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory.class);
        var policy = mock(com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.class);
        var runtime = mock(com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.class);
        var viewerPrincipal = mock(com.cretas.aims.ai.tool.gateway.ExecutionPrincipal.class);
        var keeperPrincipal = mock(com.cretas.aims.ai.tool.gateway.ExecutionPrincipal.class);
        when(principals.create(eq(FACTORY), any(), eq("viewer"))).thenReturn(viewerPrincipal);
        when(principals.create(eq(FACTORY), any(), eq("warehouse_manager"))).thenReturn(keeperPrincipal);
        when(policy.rehydrate(any())).thenAnswer(inv -> Optional.of(
                new com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal(inv.getArgument(0), Map.of())));
        when(runtime.resolve(any(), any())).thenAnswer(inv ->
                inv.getArgument(1) == keeperPrincipal
                        && approved.contains(inv.<com.cretas.aims.ai.tool.gateway.ToolExecutionCommand>getArgument(0).toolName())
                        ? Optional.of(mock(com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool.class))
                        : Optional.empty());
        ReflectionTestUtils.setField(skillExecutor, "toolPrincipalFactory", principals);
        ReflectionTestUtils.setField(skillExecutor, "toolPrincipalPolicy", policy);
        ReflectionTestUtils.setField(skillExecutor, "toolRuntimeRegistry", runtime);

        // 库里改过的定义只列一个 viewer 能用的工具; 改前预检按它判 ⇒ viewer 放行, 而执行期工作流会在网关被拒
        com.cretas.aims.dto.skill.SkillDefinition overridden = com.cretas.aims.dto.skill.SkillDefinition.builder()
                .name(com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME)
                .displayName("库存分析").tools(List.of(new CustomerListTool().getToolName())).enabled(true).build();
        assertThat(executionAllows("viewer", toolByIntent.get(CUSTOMERS)))
                .as("前提: 只看定义里的工具 viewer 是放行的").isTrue();

        assertThat(skillExecutor.permissionRefusal(overridden, skillCtx("viewer"))).isPresent();
        assertThat(skillExecutor.permissionRefusal(overridden, skillCtx("warehouse_manager")))
                .as("阴性对照: 网关放行的角色预检也放行").isEmpty();
    }

    private AIIntentConfig intentConfig(String code) {
        return configService.getIntentByCode(FACTORY, code).orElseThrow();
    }

    private com.cretas.aims.dto.skill.SkillContext skillCtx(String role) {
        return com.cretas.aims.dto.skill.SkillContext.builder().factoryId(FACTORY)
                .userId(String.valueOf(USER_OF.get(role))).userRole(role).build();
    }

    /**
     * 钉住执行期 {@code ToolDispatchService#executeWithToolInScope} 里「按调用者拒绝」的门清单。
     * 候选侧覆盖: 一线写门 / 旧角色串 / 中央 RBAC (同一个 ToolPermissionChain) + READ 模式写拦截 (modeAllowed)。
     * 工厂关掉的工具 (M1)、供应商比价身份门 (L1): 两边共用 {@link ToolExecutionAdmission#refusal}。
     * 治理不可用 (DENIED) / 写确认 / 预览不支持 / FAILED 不是「这个人能不能用」。
     * 执行期新增一道拒绝门 ⇒ 这里的计数变 ⇒ 红: 先决定候选侧要不要跟, 再改这张表。
     */
    @Test
    @DisplayName("执行期拒绝门清单钉住: 新增门必须先决定候选是否跟进")
    void executionSideGateInventoryIsPinned() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java"));
        int start = src.indexOf("    private IntentExecuteResponse executeWithToolInScope(");
        assertThat(start).as("executeWithToolInScope 必须存在").isGreaterThan(0);
        java.util.regex.Matcher next = java.util.regex.Pattern
                .compile("\\n    (private|public|protected|static)[^\\n]*\\(").matcher(src);
        assertThat(next.find(start + 10)).isTrue();
        String body = src.substring(start, next.start());

        Map<String, Integer> statuses = new java.util.TreeMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.status\\(\"([A-Z_]+)\"\\)").matcher(body);
        while (m.find()) statuses.merge(m.group(1), 1, Integer::sum);
        assertThat(statuses).as("执行期拒绝/返回状态清单变了 —— 新门要同步到 ClarificationCandidateService 或登记为已知未做")
                .isEqualTo(new java.util.TreeMap<>(Map.of(
                        "PERMISSION_DENIED", 2, "FORBIDDEN", 1, "TOOL_DISABLED", 1, "READ_MODE_WRITE_BLOCKED", 1,
                        "DENIED", 1, "WRITE_CONFIRM_REQUIRED", 2, "PREVIEW_UNSUPPORTED", 1, "FAILED", 3)));
        Map<String, Integer> gates = new LinkedHashMap<>();
        for (String g : List.of("frontlineWriteDenied(", "legacyRoleDenied(", "toolRbacEnforcer.check(",
                "isToolEnabledForFactory(", "SupplierComparisonReadIntent.", "ToolExecutionAdmission.refusal(")) {
            gates.put(g, count(body, g));
        }
        // 身份门 + 功能开关 (M1/L1) 已收进 ToolExecutionAdmission: 执行期只调它一次, 不再自己判
        // (剩下 2 处 SupplierComparisonReadIntent. 是「执行后」的结果处理, 不是准入门)
        assertThat(gates).isEqualTo(Map.of("frontlineWriteDenied(", 1, "legacyRoleDenied(", 1,
                "toolRbacEnforcer.check(", 1, "isToolEnabledForFactory(", 0, "SupplierComparisonReadIntent.", 2,
                "ToolExecutionAdmission.refusal(", 1));

        // 候选侧调的是同一个方法 (接上没有: 不是各写一份)
        String candidates = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/execution/ClarificationCandidateService.java"));
        assertThat(count(candidates, "ToolExecutionAdmission.refusal(")).isEqualTo(1);
        assertThat(count(candidates, "isToolEnabledForFactory(")).as("候选不许自抄一份开关判定").isZero();
        String admission = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/execution/ToolExecutionAdmission.java"));
        assertThat(count(admission, "isToolEnabledForFactory(")).isEqualTo(1);
        assertThat(count(admission, "AuthenticatedActor.fromCurrentExecution()")).isEqualTo(1);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
