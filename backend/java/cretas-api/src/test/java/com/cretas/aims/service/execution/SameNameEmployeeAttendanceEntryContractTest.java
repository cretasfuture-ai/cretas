package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.hr.AttendanceHistoryTool;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.TimeClockService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.ConversationMemoryServiceImpl;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 口径规则 8 (Steve 2026-11-12, INTENT-DISTILL-20261111):「查一下张三这周的考勤」, 本厂有两个张三 ——
 * 不先问, 两个都查出来列给他, 每条标明车间/部门。
 *
 * <p>跑在两个产品真实入口上 (普通 {@link IntentExecutionOrchestrator#execute} 与 SSE {@code executeStreamAsync});
 * 按姓名找人走<b>真实 JPA</b> ({@link UserRepository} + H2, {@code @Where} 软删过滤生效), 结果格式化是真的
 * {@code ResultFormatterServiceImpl}。只有识别 (模型)、考勤记录服务、权限判定是桩 —— 权限判定的桩
 * 就是「这个调用者有没有 hr:read」这一个问题。
 *
 * <p>阴性对照: 别的工厂的同名员工不出现、不被查; 没有 hr:read 的调用者拿不到别人, 回复里也看不出有几个同名的人。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("口径 8: 同名员工都列出来并标车间 (两个入口, 真实 JPA)")
class SameNameEmployeeAttendanceEntryContractTest {

    enum Entry { ORDINARY, SSE }

    private static final String FACTORY = "F-SAME-NAME-A";
    private static final String OTHER_FACTORY = "F-SAME-NAME-B";
    private static final String ROLE = "factory_super_admin";

    @Autowired TestEntityManager em;
    @Autowired UserRepository users;
    @Autowired com.cretas.aims.repository.permission.PlatformRolePermissionRepository platformRolePermissions;
    @Autowired com.cretas.aims.repository.config.FactoryModuleConfigRepository factoryModuleConfigs;

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("本厂两个张三: 两个都查、都列出来并标车间; 别的工厂的张三不出现、不被查")
    void twoSameNameEmployeesAreBothListedWithTheirWorkshop(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "hr-caller", "人事王", "人事部", null);
        User a = user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        User b = user(FACTORY, "zs-b", "张三", "包装车间", "027");
        User elsewhere = user(OTHER_FACTORY, "zs-x", "张三", "外厂车间", "099");
        user(FACTORY, "zsf", "张三丰", "加工一车间", "012");   // 名字包含「张三」但不同名
        flush();

        Harness h = new Harness(entry, caller.getId(), true);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        String text = r.getFormattedText();
        assertThat(text).as("status=%s message=%s", r.getStatus(), r.getMessage())
                .contains("本厂有 2 位叫「张三」的员工")
                .contains("车间/部门：加工一车间").contains("工号 011")
                .contains("车间/部门：包装车间").contains("工号 027")
                .doesNotContain("外厂车间").doesNotContain("张三丰");
        verify(h.clock).getClockHistory(eq(FACTORY), eq(a.getId()), any(), any(), any(PageRequest.class));
        verify(h.clock).getClockHistory(eq(FACTORY), eq(b.getId()), any(), any(), any(PageRequest.class));
        verify(h.clock, never()).getClockHistory(anyString(), eq(elsewhere.getId()), any(), any(), any());
        verify(h.clock, never()).getClockHistory(eq(OTHER_FACTORY), anyLong(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("只有一个张三: 直接查, 不列同名清单")
    void aUniqueNameIsQueriedDirectly(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "hr-caller", "人事王", "人事部", null);
        User a = user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        user(OTHER_FACTORY, "zs-x", "张三", "外厂车间", "099");
        flush();

        Harness h = new Harness(entry, caller.getId(), true);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        assertThat(r.getStatus()).as(r.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(r.getFormattedText()).doesNotContain("位叫「张三」");
        verify(h.clock).getClockHistory(eq(FACTORY), eq(a.getId()), any(), any(), any(PageRequest.class));
        verify(h.clock, org.mockito.Mockito.times(1))
                .getClockHistory(anyString(), anyLong(), any(), any(), any(PageRequest.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("同名超过 5 个: 列前 5 个, 说共几个、请说得更具体")
    void moreThanFiveMatchesListsFiveAndSaysHowMany(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "hr-caller", "人事王", "人事部", null);
        for (int i = 1; i <= 7; i++) {
            user(FACTORY, "zs-" + i, "张三", "车间" + i, String.format("%03d", i));
        }
        flush();

        Harness h = new Harness(entry, caller.getId(), true);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        assertThat(r.getFormattedText()).contains("本厂有 7 位叫「张三」的员工").contains("列出前 5 位")
                .contains("说得更具体").contains("车间/部门：车间5").doesNotContain("车间/部门：车间6");
        verify(h.clock, org.mockito.Mockito.times(5))
                .getClockHistory(eq(FACTORY), anyLong(), any(), any(), any(PageRequest.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("没有 hr:read: 拿不到别人的考勤, 回复里也看不出有几个同名的人")
    void withoutHrReadNoOtherEmployeeAndNoCountLeaks(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "op-caller", "操作员小李", "加工一车间", "050");
        user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        user(FACTORY, "zs-b", "张三", "包装车间", "027");
        flush();

        Harness h = new Harness(entry, caller.getId(), false);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        String all = r.getMessage() + " | " + r.getFormattedText();
        assertThat(r.getStatus()).isNotIn("SUCCESS", "COMPLETED");
        assertThat(all).contains("hr:read")
                .doesNotContain("2 位").doesNotContain("包装车间").doesNotContain("工号 0");
        verify(h.clock, never()).getClockHistory(anyString(), anyLong(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("没有 hr:read 但说的就是自己的名字: 只查本人, 不查同名同事")
    void withoutHrReadOwnNameQueriesOnlySelf(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "ls-self", "李四", "加工一车间", "031");
        User colleague = user(FACTORY, "ls-other", "李四", "包装车间", "032");
        flush();

        Harness h = new Harness(entry, caller.getId(), false);
        IntentExecuteResponse r = h.say("查一下李四这周的考勤", "李四");

        assertThat(r.getStatus()).as(r.getMessage()).isIn("SUCCESS", "COMPLETED");
        assertThat(r.getFormattedText()).doesNotContain("包装车间").doesNotContain("位叫「李四」");
        verify(h.clock).getClockHistory(eq(FACTORY), eq(caller.getId()), any(), any(), any(PageRequest.class));
        verify(h.clock, never()).getClockHistory(anyString(), eq(colleague.getId()), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("真实权限判定 (ToolRbacGuard + PermissionServiceImpl): hr_admin 能按姓名列出两个张三")
    void realPermissionHrAdminGetsTheSameNameList(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "hr-real", "人事王", "人事部", null, "hr_admin");
        user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        user(FACTORY, "zs-b", "张三", "包装车间", "027");
        flush();

        Harness h = new Harness(entry, caller.getId(), null, AuthenticatedActorType.FACTORY_USER);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        assertThat(r.getFormattedText()).as(r.getMessage()).contains("本厂有 2 位叫「张三」的员工")
                .contains("加工一车间").contains("包装车间");
        verify(h.clock, org.mockito.Mockito.times(2))
                .getClockHistory(eq(FACTORY), anyLong(), any(), any(), any(PageRequest.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("真实权限判定: operator 没有 hr:read, 被拒且看不出有几个同名的人")
    void realPermissionOperatorIsRefused(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "op-real", "操作员小李", "加工一车间", "050", "operator");
        user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        user(FACTORY, "zs-b", "张三", "包装车间", "027");
        flush();

        Harness h = new Harness(entry, caller.getId(), null, AuthenticatedActorType.FACTORY_USER);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        assertThat(r.getStatus()).isNotIn("SUCCESS", "COMPLETED");
        assertThat(r.getMessage() + " | " + r.getFormattedText()).contains("hr:read")
                .doesNotContain("2 位").doesNotContain("包装车间");
        verify(h.clock, never()).getClockHistory(anyString(), anyLong(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("平台账号 (数字 id 与一个 hr_admin 工厂用户相同): 不按姓名找人")
    void platformActorCannotLookUpByName(Entry entry) throws Exception {
        factories();
        // users 表里这个 id 是 hr_admin; 平台账号拿同一个数字 id 进来, 不能借他的 hr:read
        User sameIdFactoryUser = user(FACTORY, "hr-collide", "人事王", "人事部", null, "hr_admin");
        user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        user(FACTORY, "zs-b", "张三", "包装车间", "027");
        flush();

        Harness h = new Harness(entry, sameIdFactoryUser.getId(), null, AuthenticatedActorType.PLATFORM_ADMIN);
        IntentExecuteResponse r = h.say("查一下张三这周的考勤", "张三");

        // 两个入口在会话身份绑定处就拒绝了平台账号 (措辞是入口的); 工具里那道闸在下一条单独钉住
        assertThat(r.getStatus()).isNotIn("SUCCESS", "COMPLETED");
        assertThat(r.getMessage() + " | " + r.getFormattedText())
                .doesNotContain("2 位").doesNotContain("包装车间").doesNotContain("加工一车间");
        verify(h.clock, never()).getClockHistory(anyString(), anyLong(), any(), any(), any());
    }

    /**
     * 工具这一层的闸 (纵深): 即便有别的入口把平台账号带到考勤工具, 按姓名找人也必须是绑定本厂的工厂用户。
     * 入口已在前面拒绝, 所以这一条直接调工具 —— 去掉 boundFactoryUser 检查 (变异 M4) 时本条变红。
     * 权限判定是真的 ToolRbacGuard + PermissionServiceImpl: users 表里同一个 id 是 hr_admin, 本该放行。
     */
    @org.junit.jupiter.api.Test
    @DisplayName("工具层: 平台账号 id 撞上 hr_admin 工厂用户, 不能借他的 hr:read 按姓名找人")
    void toolRefusesPlatformActorEvenWhenTheSameIdIsAnHrAdmin() throws Exception {
        factories();
        User sameIdFactoryUser = user(FACTORY, "hr-collide", "人事王", "人事部", null, "hr_admin");
        user(FACTORY, "zs-a", "张三", "加工一车间", "011");
        user(FACTORY, "zs-b", "张三", "包装车间", "027");
        flush();
        Harness h = new Harness(Entry.ORDINARY, sameIdFactoryUser.getId(), null, AuthenticatedActorType.FACTORY_USER);
        AttendanceHistoryTool tool = (AttendanceHistoryTool) h.registry.getExecutor("attendance_history").orElseThrow();
        Map<String, Object> params = new HashMap<>();
        params.put("employeeName", "张三");
        params.put("userId", sameIdFactoryUser.getId());
        Map<String, Object> context = Map.of("userId", sameIdFactoryUser.getId(), "factoryId", FACTORY);

        // 阳性对照: 同样的调用, 工厂用户身份 → 两个张三都查 (证明下面的拒绝不是权限判定本身失败)
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.FACTORY_USER, sameIdFactoryUser.getId(), FACTORY, "hr_admin"))) {
            Object ok = ReflectionTestUtils.invokeMethod(tool, "doExecute", FACTORY, new HashMap<>(params), context);
            assertThat(String.valueOf(((Map<?, ?>) ok).get("message"))).contains("本厂有 2 位叫「张三」的员工");
        }
        org.mockito.Mockito.clearInvocations(h.clock);

        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, sameIdFactoryUser.getId(), FACTORY, "platform_admin"))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            ReflectionTestUtils.invokeMethod(tool, "doExecute", FACTORY, new HashMap<>(params), context))
                    .hasMessageContaining("本厂员工账号");
        }
        verify(h.clock, never()).getClockHistory(anyString(), anyLong(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("没有 hr:read, 模型把「我」填进姓名: 按本人查 (不变成 403)")
    void selfWordQueriesTheCaller(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "op-self", "操作员小李", "加工一车间", "050", "operator");
        flush();

        Harness h = new Harness(entry, caller.getId(), null, AuthenticatedActorType.FACTORY_USER);
        IntentExecuteResponse r = h.say("查我今天打卡", "我");

        assertThat(r.getStatus()).as(r.getMessage()).isIn("SUCCESS", "COMPLETED");
        verify(h.clock).getClockHistory(eq(FACTORY), eq(caller.getId()), any(), any(), any(PageRequest.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("本厂没有这个人: 明确说没找到 (外厂同名不算)")
    void noMatchSaysSo(Entry entry) throws Exception {
        factories();
        User caller = user(FACTORY, "hr-caller", "人事王", "人事部", null);
        user(OTHER_FACTORY, "ww-x", "王五", "外厂车间", "099");
        flush();

        Harness h = new Harness(entry, caller.getId(), true);
        IntentExecuteResponse r = h.say("查一下王五这周的考勤", "王五");

        assertThat(r.getFormattedText()).as(r.getMessage()).contains("本厂没有找到叫「王五」的在职员工")
                .doesNotContain("外厂车间");
        verify(h.clock, never()).getClockHistory(anyString(), anyLong(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Entry.class)
    @DisplayName("账号名只在本人没填姓名时才算本人: 有姓名时说账号名被拒, 没姓名时按本人查")
    void accountNameCountsAsSelfOnlyWithoutFullName(Entry entry) throws Exception {
        factories();
        User named = user(FACTORY, "lsm001", "操作员小李", "加工一车间", "050", "operator");
        User unnamed = user(FACTORY, "baogong007", null, "包装车间", "051", "operator");
        flush();

        IntentExecuteResponse refused = new Harness(entry, named.getId(), null, AuthenticatedActorType.FACTORY_USER)
                .say("查一下lsm001的考勤", "lsm001");
        assertThat(refused.getStatus()).isNotIn("SUCCESS", "COMPLETED");

        Harness h = new Harness(entry, unnamed.getId(), null, AuthenticatedActorType.FACTORY_USER);
        IntentExecuteResponse self = h.say("查一下baogong007的考勤", "baogong007");
        assertThat(self.getStatus()).as(self.getMessage()).isIn("SUCCESS", "COMPLETED");
        verify(h.clock).getClockHistory(eq(FACTORY), eq(unnamed.getId()), any(), any(), any(PageRequest.class));
    }

    // ------------------------------------------------------------------ fixtures

    private void factories() {
        for (String id : List.of(FACTORY, OTHER_FACTORY)) {
            Factory f = new Factory();
            f.setId(id);
            f.setName("same-name " + id);
            f.setType(FactoryType.FACTORY);
            f.setLevel(0);
            f.setIsActive(true);
            f.setManuallyVerified(false);
            f.setAiWeeklyQuota(20);
            em.persist(f);
        }
    }

    private User user(String factoryId, String username, String fullName, String department, String code) {
        return user(factoryId, username, fullName, department, code, null);
    }

    private User user(String factoryId, String username, String fullName, String department, String code,
                      String roleCode) {
        User u = new User();
        u.setRoleCode(roleCode);
        u.setFactoryId(factoryId);
        u.setUsername(username);
        u.setPasswordHash("test-only");
        u.setIsActive(true);
        u.setFullName(fullName);
        u.setDepartment(department);
        u.setEmployeeCode(code);
        em.persist(u);
        return u;
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    private final class Harness {
        private final Entry entry;
        private final long callerId;
        private final AuthenticatedActorType actorType;
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final List<Object[]> events = new ArrayList<>();
        private final AIIntentService recognizer = mock(AIIntentService.class);
        private final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        final ToolRegistry registry = mock(ToolRegistry.class);
        final TimeClockService clock = mock(TimeClockService.class);
        private final Map<String, ConversationMemory> rows = new HashMap<>();
        private final AIIntentConfig attendance;
        private IntentExecutionOrchestrator orchestrator;
        private SseStreamingService sse;
        private int sseResults;

        Harness(Entry entry, long callerId, boolean hrReader) throws Exception {
            this(entry, callerId, (Boolean) hrReader, AuthenticatedActorType.FACTORY_USER);
        }

        /** hrReader == null: 真的 ToolRbacGuard + PermissionServiceImpl, 按库里的 roleCode 判 hr:read。 */
        Harness(Entry entry, long callerId, Boolean hrReader, AuthenticatedActorType actorType) throws Exception {
            this.entry = entry;
            this.callerId = callerId;
            this.actorType = actorType;
            ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
            when(repository.findBySessionId(anyString())).thenAnswer(call ->
                    Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
            when(repository.save(any(ConversationMemory.class))).thenAnswer(call -> {
                ConversationMemory row = call.getArgument(0, ConversationMemory.class);
                rows.put(row.getSessionId(), row);
                return row;
            });
            ConversationMemoryServiceImpl memory = new ConversationMemoryServiceImpl(repository, null);
            ReflectionTestUtils.setField(memory, "windowSize", 10);
            ReflectionTestUtils.setField(memory, "summaryThreshold", 1000);
            ReflectionTestUtils.setField(memory, "expireMinutes", 60);
            memory.getOrCreateContext(FACTORY, callerId, "same-name-session");

            ToolRbacGuard guard;
            if (hrReader == null) {
                guard = new ToolRbacGuard();
                ReflectionTestUtils.setField(guard, "userRepository", users);
                // 真的权限服务, 配置层用本测试上下文里真的 JPA 仓储 (表为空 = 走平台默认矩阵)
                com.cretas.aims.service.impl.PermissionServiceImpl permissions =
                        new com.cretas.aims.service.impl.PermissionServiceImpl();
                ReflectionTestUtils.setField(permissions, "platformRepo", platformRolePermissions);
                ReflectionTestUtils.setField(permissions, "factoryConfigRepo", factoryModuleConfigs);
                ReflectionTestUtils.setField(guard, "permissionService", permissions);
            } else {
                guard = mock(ToolRbacGuard.class);
                when(guard.hasAnyPermission(any(), eq("hr:read"))).thenReturn(hrReader);
            }
            AttendanceHistoryTool tool = new AttendanceHistoryTool();
            ReflectionTestUtils.setField(tool, "objectMapper", mapper);
            ReflectionTestUtils.setField(tool, "timeClockService", clock);
            ReflectionTestUtils.setField(tool, "rbacGuard", guard);
            ReflectionTestUtils.setField(tool, "userRepository", users);
            when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.<ToolExecutor>of(tool));
            when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
            when(clock.getClockHistory(anyString(), anyLong(), any(), any(), any(PageRequest.class)))
                    .thenAnswer(call -> PageResponse.of(List.of(), 1, 20, 3L));

            when(semanticCache.queryCache(anyString(), anyString()))
                    .thenReturn(com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
            attendance = AIIntentConfig.builder().intentCode("ATTENDANCE_HISTORY").intentName("考勤历史")
                    .intentCategory("HR").businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                    .toolName("attendance_history").isActive(true).build();
            when(recognizer.getIntentByCode(FACTORY, "ATTENDANCE_HISTORY")).thenReturn(Optional.of(attendance));
            when(recognizer.getIntentByCode("ATTENDANCE_HISTORY")).thenReturn(Optional.of(attendance));
            when(recognizer.hasPermission("ATTENDANCE_HISTORY", ROLE)).thenReturn(true);
            when(recognizer.hasPermission(FACTORY, "ATTENDANCE_HISTORY", ROLE)).thenReturn(true);
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenAnswer(call -> matched(call.getArgument(0, String.class)));
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenAnswer(call -> matched(call.getArgument(0, String.class)));

            ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
            when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
            ToolDispatchService dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class),
                    redundancy, mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                    mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                    mock(ParameterExtractionLearningService.class));
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
            when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
            ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", new QueryPreprocessorServiceImpl(
                    new TimeNormalizationRules(), new ColloquialMappings(), null, null, mapper, null, null));
            ReflectionTestUtils.setField(dispatch, "conversationMemoryService", memory);
            com.cretas.aims.service.impl.ResultFormatterServiceImpl formatter =
                    new com.cretas.aims.service.impl.ResultFormatterServiceImpl();
            com.cretas.aims.security.PriceFieldResponseAdvice priceView =
                    mock(com.cretas.aims.security.PriceFieldResponseAdvice.class);
            when(priceView.maskedViewFor(any(), any())).thenAnswer(call -> call.getArgument(0));
            ReflectionTestUtils.setField(formatter, "priceView", priceView);

            IntentPermissionGate gate = mock(IntentPermissionGate.class);
            when(gate.check(any(), any(), anyString())).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            when(businessTypeGate.resolveDomain(FACTORY)).thenReturn("FACTORY");
            if (entry == Entry.ORDINARY) {
                orchestrator = new IntentExecutionOrchestrator(
                        recognizer, mock(IntentSemanticsParser.class), semanticCache,
                        mock(RuleEngineService.class), mock(ConversationService.class), memory,
                        mapper, mock(DashScopeClient.class), mock(DashScopeConfig.class),
                        mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), registry,
                        mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                        mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), dispatch,
                        mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
                ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
                ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
                IntentConfigManagementService config = mock(IntentConfigManagementService.class);
                when(config.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
                ReflectionTestUtils.setField(orchestrator, "configService", config);
                ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
                ReflectionTestUtils.setField(orchestrator, "resultFormatterService", formatter);
                ReflectionTestUtils.setField(orchestrator, "recognitionHistoryResolver",
                        new RecognitionHistoryResolver(memory));
                OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
                FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
                for (String counter : List.of("branchToolDirect", "branchSkill", "branchDynamic", "branchNoMatch")) {
                    ReflectionTestUtils.setField(orchestrator, counter, new java.util.concurrent.atomic.AtomicLong());
                }
            } else {
                FactoryRepository factories = mock(FactoryRepository.class);
                Factory factory = new Factory();
                factory.setId(FACTORY);
                factory.setType(FactoryType.FACTORY);
                factory.setIsActive(true);
                when(factories.findById(anyString())).thenReturn(Optional.of(factory));
                sse = org.mockito.Mockito.spy(new SseStreamingService(
                        recognizer, semanticCache, mock(IntentKnowledgeBase.class),
                        mock(AnalysisRouterService.class), mapper, registry, dispatch,
                        mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
                ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", new FactoryCapabilityPackRoutingPolicy(
                        factories, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false));
                ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
                ReflectionTestUtils.setField(sse, "businessTypeGate", businessTypeGate);
                ReflectionTestUtils.setField(sse, "intentPermissionGate", gate);
                ReflectionTestUtils.setField(sse, "conversationMemoryService", memory);
                ReflectionTestUtils.setField(sse, "resultFormatterService", formatter);
                org.mockito.Mockito.doAnswer(call -> {
                    events.add(new Object[]{call.getArgument(1), call.getArgument(2)});
                    return null;
                }).when(sse).sendSseEvent(any(), anyString(), any());
            }
        }

        private IntentMatchResult matched(String utterance) {
            return IntentMatchResult.builder().bestMatch(attendance).confidence(0.99d)
                    .matchMethod(IntentMatchResult.MatchMethod.KEYWORD).userInput(utterance)
                    .requiresConfirmation(false).build();
        }

        /** 姓名作为本轮参数带进来 (与 App/识别层抽出的槽位走同一条 request.context 合并路径)。 */
        IntentExecuteResponse say(String utterance, String employeeName) throws Exception {
            Map<String, Object> context = new HashMap<>();
            context.put("employeeName", employeeName);
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(utterance)
                    .sessionId("same-name-session").context(context).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(actorType, callerId, FACTORY, ROLE))) {
                if (entry == Entry.ORDINARY) {
                    return orchestrator.execute(FACTORY, request, callerId, ROLE);
                }
                ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", mock(SseEmitter.class), FACTORY, request,
                        callerId, ROLE);
            }
            Object result = null;
            for (int i = sseResults; i < events.size(); i++) {
                if ("result".equals(events.get(i)[0])) result = events.get(i)[1];
            }
            List<String> seen = events.subList(sseResults, events.size()).stream()
                    .map(e -> e[0] + "=" + String.valueOf(e[1]).substring(0, Math.min(300, String.valueOf(e[1]).length())))
                    .toList();
            sseResults = events.size();
            assertThat(result).as("SSE result event for %s; events=%s", utterance, seen)
                    .isInstanceOf(IntentExecuteResponse.class);
            return (IntentExecuteResponse) result;
        }
    }
}
