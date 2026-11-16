package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.report.SkuGrossMarginTool;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-16 复跑: sales_manager 问「哪个产品毛利率最高」/「成本里原料占比多少」/「明年一月卤牛肉的单位成本」得到
 * 「当前没有可安全提供的业务选项，请重新描述您的需求。」—— 出处是 {@link ClarificationCandidateService#clarificationMessage}
 * 的空候选分支: 识别给的候选全部被执行期同一条权限链 ({@link ToolPermissionChain}) 滤掉, 话术却不说原因。
 *
 * <p>这里用<b>真实</b>的权限链 (ToolRbacEnforcer → ToolRbacGuard, 权限码只来自账号的权限配置) 和真实的
 * SKU 毛利率工具门 (finance:read | finance:read_write)。全被权限滤掉 ⇒ PERMISSION_DENIED + 与执行期一字不差的拒答;
 * 确实没有候选 ⇒ 澄清 + 可问的示例; 有一个能用 ⇒ 照常列选项 (阴性对照)。
 */
@DisplayName("澄清候选全被权限滤掉: 说清缺什么权限 (与执行期拒答一致); 确实没有候选: 给可问的示例")
class ClarificationPermissionReasonContractTest {

    private static final String FACTORY = "F006";
    private static final long USER = 88L;
    private static final String ROLE = "sales_manager";

    private final IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final SkuGrossMarginTool skuTool = new SkuGrossMarginTool();
    private ToolRbacEnforcer enforcer;

    private ClarificationCandidateService service(Set<String> held) {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        User u = new User();
        u.setId(USER);
        u.setFactoryId(FACTORY);
        u.setRoleCode(ROLE);
        when(users.findById(USER)).thenReturn(Optional.of(u));
        when(permissions.hasAnyPermission(any(), any(String[].class))).thenAnswer(call -> {
            Set<String> asked = new HashSet<>();
            Object[] args = call.getArguments();
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof String code) asked.add(code);
                if (args[i] instanceof String[] codes) asked.addAll(Arrays.asList(codes));
            }
            return asked.stream().anyMatch(held::contains);
        });
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        FrontlineAiWriteGrantService grants = mock(FrontlineAiWriteGrantService.class);
        when(grants.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);

        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        // 这几个意图在 prod 上没配意图级权限 / 角色 (旧 requiredRoles 门放行), 能不能用由绑定工具的 RBAC 决定
        when(configService.hasPermission(anyString(), anyString(), anyString())).thenReturn(true);
        intent("SKU_GROSS_MARGIN", "SKU 毛利率", "report_sku_gross_margin", skuTool);
        intent("REPORT_SALES_MARGIN_SUMMARY", "本月销售额与毛利", "report_sales_margin_summary", skuTool);
        intent("PRODUCTION_STATUS_QUERY", "生产进度查询", "production_status_query", openTool());
        when(toolRegistry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);

        return new ClarificationCandidateService(configService, mock(IntentPermissionGate.class), accessModeFilter,
                new WriteGuardService(), grants, toolRegistry, new ToolPermissionChain(enforcer, grants));
    }

    private void intent(String code, String name, String toolName, ToolExecutor tool) {
        AIIntentConfig config = AIIntentConfig.builder().intentCode(code).intentName(name).intentCategory("QUERY")
                .businessType("COMMON").sensitivityLevel("LOW").toolName(toolName).build();
        config.setIsActive(true);
        when(configService.getIntentByCode(FACTORY, code)).thenReturn(Optional.of(config));
        when(toolRegistry.getExecutor(toolName)).thenReturn(Optional.of(tool));
    }

    /** 不要求权限的只读工具 (阴性对照用)。 */
    private static ToolExecutor openTool() {
        ToolExecutor t = mock(ToolExecutor.class);
        when(t.getToolName()).thenReturn("production_status_query");
        when(t.getRequiredRoles()).thenReturn(Set.of());
        when(t.getRequiredPermissions()).thenReturn(Set.of());
        when(t.getAlsoRequiredPermissions()).thenReturn(Set.of());
        when(t.requiresPermission()).thenReturn(false);
        when(t.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        return t;
    }

    private static IntentMatchResult match(String... codes) {
        return IntentMatchResult.builder()
                .topCandidates(Arrays.stream(codes).map(c -> IntentMatchResult.CandidateIntent.builder()
                        .intentCode(c).intentName("untrusted " + c).confidence(0.4).build()).toList())
                .confidence(0.42).userInput("哪个产品毛利率最高").build();
    }

    private static List<String> codes(ClarificationCandidateService.Clarification c) {
        return c.actions().stream().map(IntentExecuteResponse.SuggestedAction::getActionCode).toList();
    }

    @Test
    @DisplayName("无财务模块的账号: 候选全被财务门滤掉 ⇒ PERMISSION_DENIED, 文案与执行期工具拒答逐字相同")
    void allCandidatesFilteredByPermission_saysWhichPermissionIsMissing() {
        ClarificationCandidateService s = service(Set.of("sales:read", "procurement:price:view"));
        ClarificationCandidateService.Clarification c =
                s.clarify(match("SKU_GROSS_MARGIN", "REPORT_SALES_MARGIN_SUMMARY"), FACTORY, USER, ROLE, "READ");

        assertThat(c.status()).isEqualTo("PERMISSION_DENIED");
        assertThat(c.message()).contains("需要的权限").contains("财务查看").contains("财务管理")
                .doesNotContain("当前没有可安全提供的业务选项").doesNotContain("finance:");
        // 与直接执行这个工具时 ToolDispatchService W9 回的那句同一份 (同一个 enforcer、同一个上下文构造)
        java.util.Map<String, Object> trusted = new java.util.HashMap<>();
        TrustedExecutionContext.enforcePrincipal(trusted, FACTORY, USER, ROLE);
        assertThat(c.message()).isEqualTo(enforcer.check(skuTool, trusted).getMessage());
        assertThat(codes(c)).containsExactly("REPHRASE");
    }

    @Test
    @DisplayName("阴性对照: 同样的候选, 有财务查看的账号照常出选项; 有一个能用的候选时也不报权限")
    void controls_financeHolderGetsChoices_andOneUsableCandidateMeansNormalClarification() {
        ClarificationCandidateService.Clarification finance = service(Set.of("finance:read"))
                .clarify(match("SKU_GROSS_MARGIN", "REPORT_SALES_MARGIN_SUMMARY"), FACTORY, USER, ROLE, "READ");
        assertThat(finance.status()).isEqualTo("NEED_CLARIFICATION");
        assertThat(codes(finance)).containsExactly("SELECT_INTENT", "SELECT_INTENT", "REPHRASE");

        ClarificationCandidateService.Clarification mixed = service(Set.of("sales:read"))
                .clarify(match("SKU_GROSS_MARGIN", "PRODUCTION_STATUS_QUERY"), FACTORY, USER, ROLE, "READ");
        assertThat(mixed.status()).isEqualTo("NEED_CLARIFICATION");
        assertThat(mixed.message()).contains("生产进度查询").doesNotContain("需要的权限");
    }

    @Test
    @DisplayName("只看排名最高的候选 (#1788 审查 L1): 排第一的因非权限原因不出、后面的因权限不出 ⇒ 不报权限, 给示例; 反过来才报")
    void onlyTheTopRankedCandidatesPermissionDenialIsReported() {
        ClarificationCandidateService s = service(Set.of("sales:read"));
        AIIntentConfig unregistered = AIIntentConfig.builder().intentCode("TOOL_NOT_REGISTERED").intentName("未注册工具的意图")
                .intentCategory("QUERY").businessType("COMMON").sensitivityLevel("LOW").toolName("no_such_tool").build();
        unregistered.setIsActive(true);
        when(configService.getIntentByCode(FACTORY, "TOOL_NOT_REGISTERED")).thenReturn(Optional.of(unregistered));

        ClarificationCandidateService.Clarification noiseFirst =
                s.clarify(match("TOOL_NOT_REGISTERED", "SKU_GROSS_MARGIN"), FACTORY, USER, ROLE, "READ");
        assertThat(noiseFirst.status()).isEqualTo("NEED_CLARIFICATION");
        assertThat(noiseFirst.message()).isEqualTo(ClarificationCandidateService.NO_CANDIDATE_MESSAGE);

        // 阳性对照: 同两个候选, 权限被拒的那个排第一 ⇒ 报它的权限
        ClarificationCandidateService.Clarification deniedFirst =
                s.clarify(match("SKU_GROSS_MARGIN", "TOOL_NOT_REGISTERED"), FACTORY, USER, ROLE, "READ");
        assertThat(deniedFirst.status()).isEqualTo("PERMISSION_DENIED");
        assertThat(deniedFirst.message()).contains("财务查看");
    }

    @Test
    @DisplayName("确实没有候选 (本厂没配这些意图): 不报权限, 说清楚并给可问的示例")
    void noCandidateAtAll_isNotAPermissionDenial_andGivesExamples() {
        ClarificationCandidateService.Clarification c = service(Set.of("sales:read"))
                .clarify(match("NOT_CONFIGURED_A", "NOT_CONFIGURED_B"), FACTORY, USER, ROLE, "READ");
        assertThat(c.status()).isEqualTo("NEED_CLARIFICATION");
        assertThat(c.message()).isEqualTo(ClarificationCandidateService.NO_CANDIDATE_MESSAGE)
                .contains("例如").doesNotContain("需要的权限");
        assertThat(codes(c)).containsExactly("REPHRASE");
    }
}
