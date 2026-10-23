package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.config.AiFrontlineIntentGrant;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.service.skill.impl.SkillRegistryImpl;
import com.cretas.aims.service.skill.impl.SkillRouterServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-06 生产 (F006, 操作员): 「上班打卡」已被识别为 CLOCK_IN, 但 SSE 先跑了关键词 Skill
 * (人员排班, 0.30 分), 跑了管理员级的全员考勤统计, 卡没打上。另外澄清按钮给一线角色列出了没开放的写意图。
 *
 * <p>全部走真实入口 {@code SseStreamingService.executeStreamAsync}, 后面接<b>真的</b>
 * DynamicToolSelectionService / SkillRouterServiceImpl / SkillRegistryImpl (默认 Skill 全量加载) /
 * SkillExecutorImpl / ToolPermissionChain / FrontlineAiWriteGrantService / ClarificationCandidateService。
 * 只桩掉: 识别结果 (LLM)、意图配置库、工具本体 (记录被谁、带什么身份调用)、Skill 的 LLM 参数提取。
 * 意图角色取自生产 ai_intent_configs (2026-10-06 查询)。
 */
class SkillRouteGuardTest {

    private static final String FACTORY = "F006";
    private static final long OPERATOR_ID = 1686L;
    private static final long ADMIN_ID = 7L;
    private static final String OPERATOR = "operator";
    private static final String ADMIN = "factory_super_admin";

    /** 「上班打卡」对人员排班 Skill 的关键词分: 6 个触发词中 1 个 (0.6×1/6) + 覆盖 2/4 (0.4×0.5)。 */
    private static final String WEAK_CLOCK_IN = "上班打卡";
    /** 命中人员/排班/出勤 3 个触发词, 覆盖 6/6: 0.6×3/6 + 0.4×1 = 0.70。 */
    private static final String STRONG_PERSONNEL = "人员排班出勤";

    // ==================== 9 (a): 抢先路由只在「强匹配 + 角色能用每个工具」时发生 ====================

    @Test
    void operatorClockInIsExecutedAsTheRecognizedIntentNotHijackedBySkill() {
        Fixture f = new Fixture();
        assertThat(f.skillScore("personnel-scheduling", WEAK_CLOCK_IN)).isBetween(0.29, 0.31);

        Run run = f.stream(WEAK_CLOCK_IN, f.clockIn, OPERATOR_ID, OPERATOR);

        verify(f.toolDispatch).executeWithTool(same(f.tool("clock_in")), eq(FACTORY), any(), same(f.clockIn),
                eq(OPERATOR_ID), eq(OPERATOR), any());
        assertThat(run.result().getMessage()).isEqualTo("intent-tool-ran:CLOCK_IN");
        assertThat(f.executedTools()).isEmpty();
    }

    @Test
    void toolBoundIntentIsNeverPreemptedEvenByAStrongSkillMatchForAdmin() {
        // 与 /execute (IntentExecutionOrchestrator 3.5) 对齐: 意图绑了工具就由它自己的工具执行。
        Fixture f = new Fixture();
        assertThat(f.skillScore("personnel-scheduling", STRONG_PERSONNEL)).isGreaterThanOrEqualTo(0.6);

        Run run = f.stream(STRONG_PERSONNEL, f.schedulingList, ADMIN_ID, ADMIN);

        verify(f.toolDispatch).executeWithTool(same(f.tool("scheduling_list")), eq(FACTORY), any(),
                same(f.schedulingList), eq(ADMIN_ID), eq(ADMIN), any());
        assertThat(run.result().getMessage()).isEqualTo("intent-tool-ran:SCHEDULING_LIST");
        assertThat(f.executedTools()).isEmpty();
    }

    @Test
    void strongSkillMatchForAdminOnToolLessIntentStillRunsTheSkill() {
        // 阳性对照: 设计上的用途 (意图没绑工具, Skill 是它的执行器) 仍然可用。
        Fixture f = new Fixture();

        Run run = f.stream(STRONG_PERSONNEL, f.personnelOverview, ADMIN_ID, ADMIN);

        assertThat(f.executedTools()).containsExactly("scheduling_list", "attendance_stats", "query_online_staff_count");
        assertThat(f.tool("query_online_staff_count").calls.get(0).get("userRole")).isEqualTo(ADMIN);
        assertThat(run.result().getStatus()).isEqualTo("SUCCESS");
        verify(f.toolDispatch, never()).executeWithTool(any(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void weakSkillMatchDoesNotPreemptEvenForAdminOnToolLessIntent() {
        // 隔离阈值: 管理员能用全部三个工具, 挡住它的只能是分数。对照 = 上一条 (同角色同意图, 强匹配就跑)。
        Fixture f = new Fixture();

        Run run = f.stream(WEAK_CLOCK_IN, f.personnelOverview, ADMIN_ID, ADMIN);

        assertThat(f.executedTools()).isEmpty();
        // 必发生: 交回了正常流程 (意图自己的执行出口), 不是半路崩了
        assertThat(run.result().getMessage()).isEqualTo("no-executor:PERSONNEL_OVERVIEW");
    }

    @Test
    void strongSkillMatchDoesNotPreemptForOperatorWhoCannotUseItsTools() {
        // 隔离角色预检: 分数够 (与管理员那条完全同一句话、同一个意图), 挡住它的只能是角色。
        Fixture f = new Fixture();

        Run run = f.stream(STRONG_PERSONNEL, f.personnelOverview, OPERATOR_ID, OPERATOR);

        assertThat(f.executedTools()).isEmpty();
        assertThat(run.result().getMessage()).isEqualTo("no-executor:PERSONNEL_OVERVIEW");
        SkillDefinition skill = f.registry.getSkill("personnel-scheduling").orElseThrow();
        assertThat(f.dynamic.skillPreemptionBlocker(skill, FACTORY, OPERATOR_ID, OPERATOR))
                .hasValueSatisfying(reason -> assertThat(reason).contains("scheduling_list"));
        assertThat(f.dynamic.skillPreemptionBlocker(skill, FACTORY, ADMIN_ID, ADMIN)).isEmpty();
    }

    @Test
    void frontlineRoleCannotPreemptThroughAToolThatNoIntentGrants() {
        // 9 (c): query_online_staff_count 没有任何意图绑定 —— 一线角色经意图拿不到它, 经 Skill 也不行。
        Fixture f = new Fixture();
        SkillDefinition orphanOnly = SkillDefinition.builder().name("orphan-only").tools(List.of("query_online_staff_count"))
                .triggers(List.of("x")).build();

        assertThat(f.dynamic.skillPreemptionBlocker(orphanOnly, FACTORY, OPERATOR_ID, OPERATOR))
                .hasValueSatisfying(reason -> assertThat(reason).contains("query_online_staff_count"));
        // 对照: 非一线角色不被这一条挡 (经意图也走得到动态选择, 不收紧)
        assertThat(f.dynamic.skillPreemptionBlocker(orphanOnly, FACTORY, ADMIN_ID, ADMIN)).isEmpty();
    }

    @Test
    void toolLessIntentTheUserMayNotUseDoesNotReachItsSkill() {
        // SSE 7a 的显式 Skill 路由此前在意图权限检查之前 —— 没权限的意图也能先把 Skill 跑掉。
        Fixture f = new Fixture();

        Run run = f.stream("管理报表", f.adminReport, OPERATOR_ID, OPERATOR);

        assertThat(run.result().getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(f.executedTools()).isEmpty();
    }

    @Test
    void toolLessIntentTheUserMayUseReachesItsSkill() {
        // 对照: 同一个意图, 有权的人走到了 Skill。
        Fixture f = new Fixture();

        f.stream("管理报表", f.adminReport, ADMIN_ID, ADMIN);

        assertThat(f.executedTools()).contains("team_board_query");
    }

    // ==================== 9 (b): Skill 里每个工具过与 executeWithTool 同一条链 ====================

    @Test
    void operatorRunningASkillDoesNotExecuteTheAdminOnlyToolInside() {
        Fixture f = new Fixture();

        Run run = f.stream("班组看板", f.teamReport, OPERATOR_ID, OPERATOR);

        // 必发生: 同一个 Skill 里开放给他的那个工具照常跑了 (证明 Skill 确实执行了)
        assertThat(f.executedTools()).containsExactly("team_board_query");
        assertThat(f.tool("staff_overview_query").calls).isEmpty();
        // 拒绝的说法与 executeWithTool 的旧角色串拒绝同一套措辞
        assertThat(String.valueOf(run.result().getResultData()))
                .contains(ToolPermissionChain.legacyRoleDeniedMessage(OPERATOR, "staff_overview_query"));
    }

    @Test
    void adminRunningTheSameSkillExecutesTheAdminOnlyTool() {
        Fixture f = new Fixture();

        f.stream("班组看板", f.teamReport, ADMIN_ID, ADMIN);

        assertThat(f.executedTools()).containsExactly("team_board_query", "staff_overview_query");
    }

    @Test
    void llmCannotPlanAToolOutsideTheSkillsDeclaration() {
        Fixture f = new Fixture();
        when(f.skillLlm.chat(anyString(), anyString())).thenReturn(
                "{\"tools_to_execute\":[{\"tool_name\":\"payroll_export_query\"}]}");

        f.stream("班组看板", f.teamReport, ADMIN_ID, ADMIN);

        assertThat(f.tool("payroll_export_query").calls).isEmpty();
        // 必发生: LLM 的计划被丢弃后按 Skill 自己声明的工具执行
        assertThat(f.executedTools()).containsExactly("team_board_query", "staff_overview_query");
    }

    @Test
    void llmExtractedIdentityNeverReachesTheTool() {
        Fixture f = new Fixture();
        when(f.skillLlm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"userRole\":\"factory_super_admin\",\"role\":\"factory_super_admin\",\"userId\":7}}");

        f.stream("班组看板", f.teamReport, OPERATOR_ID, OPERATOR);

        Map<String, Object> seen = f.tool("team_board_query").calls.get(0);
        assertThat(seen.get("userRole")).isEqualTo(OPERATOR);
        assertThat(seen.get("role")).isEqualTo(OPERATOR);
        assertThat(seen.get("userId")).isEqualTo(OPERATOR_ID);
        // 被拒的仍被拒 —— 伪造的 role 没让旧角色串判定放行
        assertThat(f.tool("staff_overview_query").calls).isEmpty();
    }

    // ==================== 10: 澄清按钮不给一线角色未开放的写意图 ====================

    @Test
    void clarificationButtonsHideWriteIntentsNotOpenToTheFrontlineRole() {
        Fixture f = new Fixture();

        List<String> names = f.clarificationButtons(OPERATOR_ID, OPERATOR);

        assertThat(names).containsExactly("上班打卡", "排班查看");
    }

    @Test
    void clarificationButtonsStillOfferThoseWriteIntentsToAdmin() {
        Fixture f = new Fixture();

        List<String> names = f.clarificationButtons(ADMIN_ID, ADMIN);

        assertThat(names).containsExactly("创建生产批次", "上班打卡", "排班查看");
    }

    @Test
    void clarificationButtonsOfferAWriteIntentTheFactoryOpenedForTheRole() {
        Fixture f = new Fixture();
        when(f.grantRepo.findByFactoryIdAndRevokedAtIsNull(FACTORY)).thenReturn(List.of(
                AiFrontlineIntentGrant.builder().factoryId(FACTORY).roleCode(OPERATOR)
                        .intentCode("PRODUCTION_BATCH_CREATE").build()));

        List<String> names = f.clarificationButtons(OPERATOR_ID, OPERATOR);

        assertThat(names).containsExactly("创建生产批次", "上班打卡", "排班查看");
    }

    // ==================== fixture ====================

    record Run(IntentExecuteResponse result) { }

    /** 记录每次调用时拿到的执行上下文。 */
    static final class FakeTool implements ToolExecutor {
        final String name;
        final AccessMode mode;
        final Set<String> legacyRoles;   // 非空 = 旧式角色串工具, 只给这些角色
        final List<Map<String, Object>> calls = Collections.synchronizedList(new ArrayList<>());
        final List<String> journal;

        FakeTool(String name, AccessMode mode, Set<String> legacyRoles, List<String> journal) {
            this.name = name;
            this.mode = mode;
            this.legacyRoles = legacyRoles;
            this.journal = journal;
        }

        @Override public String getToolName() { return name; }
        @Override public String getDescription() { return name; }
        @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
        @Override public AccessMode getAccessMode() { return mode; }
        @Override public boolean requiresPermission() { return !legacyRoles.isEmpty(); }
        @Override public boolean hasPermission(String userRole) { return legacyRoles.isEmpty() || legacyRoles.contains(userRole); }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) {
            calls.add(new HashMap<>(context));
            journal.add(name);
            return "{\"message\":\"" + name + " ok\"}";
        }
    }

    static final class Fixture {
        final List<String> journal = Collections.synchronizedList(new ArrayList<>());
        final Map<String, FakeTool> tools = new LinkedHashMap<>();
        final Map<String, List<String>> intentRoles = new HashMap<>();
        final List<AIIntentConfig> intents = new ArrayList<>();

        final AIIntentService aiIntentService = mock(AIIntentService.class);
        final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final ToolDispatchService toolDispatch = mock(ToolDispatchService.class);
        final AiFrontlineIntentGrantRepository grantRepo = mock(AiFrontlineIntentGrantRepository.class);
        final DashScopeClient skillLlm = mock(DashScopeClient.class);
        final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        final SkillRegistryImpl registry;
        final DynamicToolSelectionService dynamic;
        final ClarificationCandidateService candidates;
        final SseStreamingService service;

        final AIIntentConfig clockIn;
        final AIIntentConfig schedulingList;
        final AIIntentConfig personnelOverview;
        final AIIntentConfig adminReport;
        final AIIntentConfig teamReport;
        final AIIntentConfig productionBatchCreate;

        Fixture() {
            List<String> all = List.of(ADMIN, OPERATOR);
            List<String> adminOnly = List.of(ADMIN, "department_admin");
            // 生产 ai_intent_configs.required_roles (2026-10-06)
            clockIn = intent("CLOCK_IN", "上班打卡", "clock_in", List.of(ADMIN, "department_admin",
                    "workshop_supervisor", OPERATOR, "quality_inspector", "yield_operator"));
            schedulingList = intent("SCHEDULING_LIST", "排班查看", "scheduling_list",
                    List.of(ADMIN, "department_admin", "workshop_supervisor", "dispatcher"));
            intent("ATTENDANCE_STATS", "考勤统计", "attendance_stats", adminOnly);
            // 无工具意图 (WORKDESK 类), Skill 就是它的执行器
            personnelOverview = intent("PERSONNEL_OVERVIEW", "人员概况", null, all);
            adminReport = intent("ADMIN_REPORT", "管理报表", null, adminOnly);
            teamReport = intent("TEAM_REPORT", "班组看板", null, all);
            productionBatchCreate = intent("PRODUCTION_BATCH_CREATE", "创建生产批次", "production_batch_create", all);
            intent("TEAM_BOARD_QUERY", "班组看板查询", "team_board_query", all);
            intent("STAFF_OVERVIEW_QUERY", "员工总览", "staff_overview_query", all);

            tool("clock_in", ToolExecutor.AccessMode.WRITE, Set.of());
            tool("scheduling_list", ToolExecutor.AccessMode.READ, Set.of());
            tool("attendance_stats", ToolExecutor.AccessMode.READ, Set.of());
            tool("query_online_staff_count", ToolExecutor.AccessMode.READ, Set.of());
            tool("team_board_query", ToolExecutor.AccessMode.READ, Set.of());
            tool("staff_overview_query", ToolExecutor.AccessMode.READ, Set.of(ADMIN));
            tool("payroll_export_query", ToolExecutor.AccessMode.READ, Set.of());
            tool("production_batch_create", ToolExecutor.AccessMode.WRITE, Set.of());
            when(toolRegistry.getExecutor(anyString())).thenAnswer(inv -> Optional.ofNullable(tools.get(inv.<String>getArgument(0))));
            when(toolRegistry.hasExecutor(anyString())).thenAnswer(inv -> tools.containsKey(inv.<String>getArgument(0)));
            when(toolRegistry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);

            when(configService.getAllIntents(FACTORY)).thenReturn(intents);
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
            when(configService.getIntentByCode(eq(FACTORY), anyString())).thenAnswer(inv -> byCode(inv.getArgument(1)));
            when(configService.hasPermission(eq(FACTORY), anyString(), anyString()))
                    .thenAnswer(inv -> allowed(inv.getArgument(1), inv.getArgument(2)));
            when(aiIntentService.hasPermission(anyString(), anyString()))
                    .thenAnswer(inv -> allowed(inv.getArgument(0), inv.getArgument(1)));
            when(aiIntentService.getIntentByCode(eq(FACTORY), anyString())).thenAnswer(inv -> byCode(inv.getArgument(1)));
            when(grantRepo.findByFactoryIdAndRevokedAtIsNull(FACTORY)).thenReturn(List.of());
            when(semanticCache.queryCache(eq(FACTORY), anyString())).thenReturn(SemanticCacheHit.miss(0L));

            WriteGuardService writeGuard = new WriteGuardService();
            FrontlineAiWriteGrantService frontline = new FrontlineAiWriteGrantService(
                    grantRepo, writeGuard, configService, toolRegistry);
            ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
            ToolRbacGuard guard = mock(ToolRbacGuard.class);
            when(guard.hasAnyPermission(any(), any(String[].class))).thenReturn(true);
            ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
            ToolPermissionChain chain = new ToolPermissionChain(enforcer, frontline);
            IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
            IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
            candidates = new ClarificationCandidateService(configService, permissionGate, accessModeFilter,
                    writeGuard, frontline, toolRegistry);

            // 真 Skill 栈: 默认 Skill + classpath SKILL.md 全量 + 两个测试用无工具意图的 Skill
            registry = new SkillRegistryImpl(mock(SmartBiSkillRepository.class), new ObjectMapper());
            ReflectionTestUtils.setField(registry, "loadDefaults", true);
            ReflectionTestUtils.setField(registry, "skillsDirectory", "classpath:skills/");
            ReflectionTestUtils.setField(registry, "defaultMinMatchScore", 0.1);
            registry.loadSkills();
            registry.register(SkillDefinition.builder().name("admin-report").displayName("管理报表")
                    .triggers(List.of("测试专用触发词甲")).tools(List.of("team_board_query"))
                    .contextNeeded(List.of("factoryId")).promptTemplate("${userQuery}").build());
            registry.register(SkillDefinition.builder().name("team-report").displayName("班组看板")
                    .triggers(List.of("测试专用触发词乙")).tools(List.of("team_board_query", "staff_overview_query"))
                    .contextNeeded(List.of("factoryId")).promptTemplate("${userQuery}").build());
            SkillExecutorImpl executor = new SkillExecutorImpl(toolRegistry, skillLlm, new ObjectMapper());
            ReflectionTestUtils.setField(executor, "writeGuardService", writeGuard);
            ReflectionTestUtils.setField(executor, "toolPermissionChain", chain);
            ReflectionTestUtils.setField(executor, "parallelExecutionEnabled", false);
            ReflectionTestUtils.setField(executor, "maxToolsPerSkill", 5);
            ReflectionTestUtils.setField(executor, "defaultTimeoutMs", 60_000L);
            ReflectionTestUtils.setField(executor, "toolTimeoutSeconds", 30);
            SkillRouterServiceImpl router = new SkillRouterServiceImpl(registry, executor, aiIntentService,
                    mock(ToolRouterService.class));
            ReflectionTestUtils.setField(router, "skillsEnabled", true);
            ReflectionTestUtils.setField(router, "minSkillConfidence", 0.3);

            dynamic = new DynamicToolSelectionService(mock(ToolRouterService.class), toolRegistry, new ObjectMapper());
            ReflectionTestUtils.setField(dynamic, "skillRouterService", router);
            ReflectionTestUtils.setField(dynamic, "configService", configService);
            ReflectionTestUtils.setField(dynamic, "toolPermissionChain", chain);
            ReflectionTestUtils.setField(dynamic, "clarificationCandidateService", candidates);

            when(toolDispatch.executeWithTool(any(), anyString(), any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> IntentExecuteResponse.builder().intentRecognized(true)
                            .status("COMPLETED")
                            .message("intent-tool-ran:" + inv.<AIIntentConfig>getArgument(3).getIntentCode()).build());
            when(toolDispatch.buildNoToolResponse(any())).thenAnswer(inv -> IntentExecuteResponse.builder()
                    .intentRecognized(true).status("FAILED")
                    .message("no-executor:" + inv.<AIIntentConfig>getArgument(0).getIntentCode()).build());

            FactoryRepository factoryRepository = mock(FactoryRepository.class);
            Factory factory = new Factory();
            factory.setId(FACTORY);
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));
            FactoryCapabilityPackRoutingPolicy packPolicy = new FactoryCapabilityPackRoutingPolicy(
                    factoryRepository, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false);

            service = spy(new SseStreamingService(aiIntentService, semanticCache, mock(IntentKnowledgeBase.class),
                    mock(AnalysisRouterService.class), new ObjectMapper(), toolRegistry, toolDispatch, dynamic,
                    mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", packPolicy);
            ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
            ReflectionTestUtils.setField(service, "writeGuardService", writeGuard);
            ReflectionTestUtils.setField(service, "clarificationCandidateService", candidates);
        }

        private AIIntentConfig intent(String code, String name, String toolName, List<String> roles) {
            AIIntentConfig c = AIIntentConfig.builder().intentCode(code).intentName(name).toolName(toolName)
                    .intentCategory("QUERY").businessType("FACTORY").sensitivityLevel("LOW").isActive(true).build();
            intents.add(c);
            intentRoles.put(code, roles);
            return c;
        }

        private Optional<AIIntentConfig> byCode(String code) {
            return intents.stream().filter(i -> i.getIntentCode().equals(code)).findFirst();
        }

        private boolean allowed(String code, String role) {
            return intentRoles.getOrDefault(code, List.of()).contains(role);
        }

        private void tool(String name, ToolExecutor.AccessMode mode, Set<String> legacyRoles) {
            tools.put(name, new FakeTool(name, mode, legacyRoles, journal));
        }

        FakeTool tool(String name) {
            return tools.get(name);
        }

        List<String> executedTools() {
            return new ArrayList<>(journal);
        }

        double skillScore(String skill, String query) {
            return registry.getSkill(skill).orElseThrow().calculateMatchScore(query);
        }

        Run stream(String userInput, AIIntentConfig recognized, long userId, String role) {
            IntentMatchResult match = IntentMatchResult.builder().bestMatch(recognized).confidence(0.98)
                    .matchMethod(IntentMatchResult.MatchMethod.LLM).requiresConfirmation(false)
                    .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build();
            when(aiIntentService.recognizeIntentWithConfidence(eq(userInput), eq(FACTORY), eq(3), eq(userId),
                    eq(role), any(), any(), any(), any())).thenReturn(match);
            List<Object> results = captureResults();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, FACTORY, role))) {
                ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                        IntentExecuteRequest.builder().userInput(userInput).build(), userId, role,
                        new F006BossQuestionClient.Cancellation());
            }
            assertThat(results).as("真实入口必须流出一个 result").hasSize(1);
            return new Run((IntentExecuteResponse) results.get(0));
        }

        List<String> clarificationButtons(long userId, String role) {
            IntentMatchResult match = IntentMatchResult.builder().bestMatch(clockIn).confidence(0.39)
                    .matchMethod(IntentMatchResult.MatchMethod.LLM).requiresConfirmation(true)
                    .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                    .topCandidates(List.of(
                            candidate("PRODUCTION_BATCH_CREATE", 0.39),
                            candidate("CLOCK_IN", 0.38),
                            candidate("SCHEDULING_LIST", 0.30)))
                    .build();
            when(aiIntentService.recognizeIntentWithConfidence(eq("1827"), eq(FACTORY), eq(3), eq(userId),
                    eq(role), any(), any(), any(), any())).thenReturn(match);
            // 排班查看对操作员在生产上不开放 —— 本组只看「写意图」这一维, 把它对两个角色都放开
            intentRoles.put("SCHEDULING_LIST", List.of(ADMIN, OPERATOR));
            List<Object> results = captureResults();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, FACTORY, role))) {
                ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                        IntentExecuteRequest.builder().userInput("1827").build(), userId, role,
                        new F006BossQuestionClient.Cancellation());
            }
            assertThat(results).hasSize(1);
            IntentExecuteResponse response = (IntentExecuteResponse) results.get(0);
            assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
            return response.getSuggestedActions().stream()
                    .filter(a -> "SELECT_INTENT".equals(a.getActionCode()))
                    .map(IntentExecuteResponse.SuggestedAction::getActionName)
                    .toList();
        }

        private List<Object> captureResults() {
            List<Object> results = new ArrayList<>();
            try {
                doAnswer(inv -> {
                    if ("result".equals(inv.getArgument(1))) results.add(inv.getArgument(2));
                    return null;
                }).when(service).sendSseEvent(any(), anyString(), any());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return results;
        }

        private static IntentMatchResult.CandidateIntent candidate(String code, double confidence) {
            return IntentMatchResult.CandidateIntent.builder().intentCode(code).intentName(code)
                    .confidence(confidence).build();
        }
    }
}
