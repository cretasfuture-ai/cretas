package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.report.ReportFinanceTool;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.controller.ReportController;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.service.*;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ERP-S6-LLM-PROMPT-PRICE-LEAK (acc-s6 复测 #1394, F006, s610j.log): 「查看财务报表」→ REPORT_FINANCE, 生产 / 销售 /
 * 采购主管的字段已遮, 回复却是模型照原始结果写的「总成本合计 3.00 元，设备成本 2.00 元，其他成本 1.00 元…总利润 -3.00 元」。
 *
 * <p>测在<b>发给模型的那一刻</b>: 真实 {@code IntentExecutionOrchestrator.execute} → 工具结果 → 真实
 * {@link PriceFieldResponseAdvice} (prod L1 配置的 {@link PermissionServiceImpl}) → 真实格式化器 → 真实
 * {@link WorkdeskOutputSummarizer}; 抓 {@code DashScopeClient.chatFast} 收到的提示词。非财务的人, 提示词里
 * 一个被遮的数都不许有; 财务 / 超管照常看得到。
 */
class FinanceReportLlmPromptPriceContractTest {

    private static final String F = "F006";
    private static final String QUESTION = "查看财务报表";
    /** prod platform_role_permissions (2026-11-03 只读核对; F006 没有这些角色的 L2 覆盖)。 */
    private static final Map<String, Map<String, String>> L1 = Map.of(
            "factory_super_admin", Map.of("analytics", "rw", "finance", "rw", "procurement", "rw", "sales", "rw"),
            "finance_manager", Map.of("analytics", "rw", "finance", "rw", "procurement", "-", "sales", "-"),
            "department_admin", Map.of("analytics", "rw", "finance", "r", "procurement", "r", "sales", "rw"),
            "procurement_manager", Map.of("analytics", "-", "finance", "-", "procurement", "rw", "sales", "-"),
            "production_manager", Map.of("analytics", "-", "finance", "-", "procurement", "-", "sales", "-"),
            "sales_manager", Map.of("analytics", "-", "finance", "-", "procurement", "-", "sales", "rw"));
    /** 线上读到的那组数; 非财务的人, 提示词里一个都不许出现。 */
    private static final List<String> MASKED_VALUES = List.of("3.00", "2.00", "1.00", "-3.00", "100.00");

    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, Long> idOf = new HashMap<>();
    private PermissionServiceImpl permissions;
    private PriceFieldResponseAdvice advice;
    private AIIntentService aiIntentService;
    private ToolDispatchService toolDispatch;
    private ToolRegistry toolRegistry;
    private DashScopeClient llm;
    private AIAnalysisResultRepository analysisResults;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        PlatformRolePermissionRepository l1 = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository l2 = mock(FactoryModuleConfigRepository.class);
        permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "platformRepo", l1);
        ReflectionTestUtils.setField(permissions, "factoryConfigRepo", l2);
        when(l2.findByFactoryIdAndConfigVersion(anyString(), anyInt())).thenReturn(List.of());
        when(l1.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(inv -> {
            String level = L1.getOrDefault(inv.getArgument(0), Map.of()).get(inv.getArgument(1));
            if (level == null) return Optional.empty();
            PlatformRolePermission row = new PlatformRolePermission();
            row.setRoleCode(inv.getArgument(0));
            row.setModuleCode(inv.getArgument(1));
            row.setPermissionLevel(level);
            return Optional.of(row);
        });
        long id = 500;
        for (String role : L1.keySet()) {
            User u = new User();
            u.setId(++id);
            u.setFactoryId(F);
            u.setUsername("fin-" + role);
            u.setRoleCode(role);
            u.setIsActive(true);
            when(users.findById(id)).thenReturn(Optional.of(u));
            idOf.put(role, id);
        }
        ObjectMapper json = new ObjectMapper();
        advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "objectMapper", json);

        aiIntentService = mock(AIIntentService.class);
        toolDispatch = mock(ToolDispatchService.class);
        toolRegistry = mock(ToolRegistry.class);
        llm = mock(DashScopeClient.class);
        analysisResults = mock(AIAnalysisResultRepository.class);
        DynamicToolSelectionService dynamic = mock(DynamicToolSelectionService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService, mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class), mock(ConversationMemoryService.class),
                json, llm, mock(DashScopeConfig.class), mock(IntentKnowledgeBase.class), analysisResults,
                toolRegistry, mock(AnalysisRouterService.class), mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                toolDispatch, dynamic, mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);

        ResultFormatterServiceImpl formatter = new ResultFormatterServiceImpl();
        ReflectionTestUtils.setField(formatter, "priceView", advice);
        ReflectionTestUtils.setField(orchestrator, "resultFormatterService", formatter);
        FactoryNumberProvenance gate = new FactoryNumberProvenance();
        WorkdeskOutputSummarizer summarizer = new WorkdeskOutputSummarizer(llm, json);
        ReflectionTestUtils.setField(summarizer, "enabled", true);
        ReflectionTestUtils.setField(summarizer, "maxChars", 800);
        ReflectionTestUtils.setField(summarizer, "numberProvenance", gate);
        ReflectionTestUtils.setField(orchestrator, "workdeskOutputSummarizer", summarizer);
        ReflectionTestUtils.setField(orchestrator, "numberProvenance", gate);
        ReflectionTestUtils.setField(orchestrator, "priceView", advice);

        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("REPORT_FINANCE");
        intent.setIntentName("财务报表");
        intent.setIntentCategory("REPORT");
        intent.setSensitivityLevel("MEDIUM");
        intent.setToolName("report_finance");
        when(aiIntentService.recognizeIntentWithConfidence(eq(QUESTION), eq(F), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(intent).confidence(0.95)
                        .matchMethod(IntentMatchResult.MatchMethod.LLM).requiresConfirmation(false).build());
        when(aiIntentService.hasPermission(eq("REPORT_FINANCE"), anyString())).thenReturn(true);
        ToolExecutor tool = mock(ToolExecutor.class);
        when(toolRegistry.getExecutor("report_finance")).thenReturn(Optional.of(tool));
        when(toolDispatch.executeWithTool(eq(tool), eq(F), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> financeResult());
        when(llm.chatFast(anyString(), anyString())).thenReturn("财务报表已生成，主要指标见下方数据卡片。");
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void tearDown() {
        PriceSensitiveContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest(name = "{0}: 发给模型的提示词里没有被遮的数")
    @ValueSource(strings = {"production_manager", "sales_manager", "procurement_manager", "department_admin"})
    void nonFinanceCaller_theLlmNeverSeesTheMaskedValues(String role) {
        IntentExecuteResponse response = orchestrator.execute(F,
                IntentExecuteRequest.builder().userInput(QUESTION).build(), idOf.get(role), role);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        // 一定发生: 字段遮过之后格式化器只剩空模板, 摘要模型确实被叫了 (不是因为没调用才没数)
        verify(llm, atLeastOnce()).chatFast(system.capture(), prompt.capture());
        String sent = String.join("\n", system.getAllValues()) + "\n" + String.join("\n", prompt.getAllValues());
        assertThat(sent).as("role=%s 发给模型的内容", role).contains("FINANCE");
        for (String value : MASKED_VALUES) {
            assertThat(sent).as("role=%s 提示词不能含 %s:\n%s", role, value, sent).doesNotContain(value);
        }
        String reply = response.getFormattedText() + "\n" + response.getMessage();
        for (String value : MASKED_VALUES) {
            assertThat(reply).as("role=%s 回复不能含 %s", role, value).doesNotContain(value);
        }
    }

    /** Skill 组合结果里的子工具结果是 JsonNode (线上形状): 一样先遮再交给模型。 */
    @Test
    void skillResultAsJsonNode_isMaskedBeforeTheLlmToo() throws Exception {
        // 用不会与日期 / 计数撞的金额; JsonNode 进提示词时数字写法会变 (3.00 → 3.0), 所以按数值匹配, 不按字面
        when(toolDispatch.executeWithTool(any(), eq(F), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            IntentExecuteResponse r = financeResult();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalRevenue", new BigDecimal("18000.50"));
            data.put("totalCost", new BigDecimal("3442.39"));
            data.put("equipmentCost", new BigDecimal("2896.71"));
            data.put("otherCost", new BigDecimal("545.68"));
            data.put("totalProfit", new BigDecimal("-3442.39"));
            @SuppressWarnings("unchecked")
            Map<String, Object> report = new LinkedHashMap<>((Map<String, Object>) r.getResultData());
            report.put("data", data);
            Map<String, Object> composite = new LinkedHashMap<>();
            composite.put("report_finance", new ObjectMapper().valueToTree(report));
            composite.put("_toolCount", 1);
            r.setResultData(composite);
            return r;
        });
        orchestrator.execute(F, IntentExecuteRequest.builder().userInput(QUESTION).build(),
                idOf.get("production_manager"), "production_manager");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, atLeastOnce()).chatFast(anyString(), prompt.capture());
        String sent = String.join("\n", prompt.getAllValues());
        assertThat(sent).contains("FINANCE");
        assertThat(sent).as("JsonNode 结果的提示词不能含被遮的金额:\n%s", sent)
                .doesNotContainPattern("(?<![\\d.])(18000\\.50?|3442\\.39|2896\\.71|545\\.68)(?!\\d)");
    }

    @ParameterizedTest(name = "{0}: 照常看得到 (对照)")
    @ValueSource(strings = {"finance_manager", "factory_super_admin"})
    void financeOrAdmin_stillGetsTheValues(String role) {
        IntentExecuteResponse response = orchestrator.execute(F,
                IntentExecuteRequest.builder().userInput(QUESTION).build(), idOf.get(role), role);

        assertThat(response.getFormattedText())
                .contains("净利润: ¥-3.00")
                .contains("设备¥2.00")
                .contains("其他¥1.00");
    }

    /** 判定组件没装配 ⇒ 结果数据不交给摘要模型 (fail closed)。 */
    @Test
    void withoutThePriceComponent_theSummarizerIsNotCalled() {
        ReflectionTestUtils.setField(orchestrator, "priceView", null);
        orchestrator.execute(F, IntentExecuteRequest.builder().userInput(QUESTION).build(),
                idOf.get("production_manager"), "production_manager");
        verify(llm, org.mockito.Mockito.never()).chatFast(anyString(), anyString());
    }

    /** 存档的日报 / 周报是整段文字, 只拼给看得到经营财务数字 (财务读写 + 价格) 的人。 */
    @ParameterizedTest(name = "{0}: 存档报告拼不拼进提示词")
    @ValueSource(strings = {"production_manager", "sales_manager", "procurement_manager", "department_admin",
            "finance_manager", "factory_super_admin"})
    void storedAnalysisReportsOnlyForCallersWhoSeeEverything(String role) {
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope scope =
                mock(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.class);
        when(scope.forCurrentCaller(F)).thenReturn(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded.NONE);
        ReflectionTestUtils.setField(orchestrator, "llmCostScope", scope);
        AIAnalysisResult daily = new AIAnalysisResult();
        daily.setAnalysisText("本日营收 ¥100.00, 总成本 ¥3.00");
        when(analysisResults.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(eq(F), eq("daily"), any()))
                .thenReturn(Optional.of(daily));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", idOf.get(role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        String context = ReflectionTestUtils.invokeMethod(orchestrator, "getPrecomputedAnalysisContext", F);

        boolean seesAll = Set.of("finance_manager", "factory_super_admin").contains(role);
        if (seesAll) {
            assertThat(context).as("role=%s", role).contains("¥3.00");
        } else {
            assertThat(context).as("role=%s", role).isNull();
        }
    }

    /**
     * (2) 应收 / 应付 / 客户预收: App 里只在「经营分析」页 (web 路由 module=analytics) 出现。对话工具除了与
     * REST 同一组门槛, 还要经营分析模块 —— 生产 / 销售 / 采购主管 (analytics「-」) 拿不到, 与 App 一致。
     */
    @Test
    void reportFinanceInChatRequiresTheSameModuleAsTheWebPage() throws Exception {
        ReportFinanceTool tool = new ReportFinanceTool();
        assertThat(tool.getRequiredPermissions()).containsExactlyInAnyOrder(
                ReportController.class.getAnnotation(RequirePermission.class).value());
        assertThat(tool.getAlsoRequiredPermissions()).containsExactlyInAnyOrder("analytics:read", "analytics:read_write");

        String route = Files.readString(Path.of("../../../web-admin/src/router/modules/smartbi.ts"), StandardCharsets.UTF_8);
        int hub = route.indexOf("path: 'analysis-hub'");
        assertThat(hub).as("web 经营分析页路由").isPositive();
        assertThat(route.substring(hub, route.indexOf("}", route.indexOf("meta", hub))))
                .as("经营分析页的模块门槛").contains("module: 'analytics'");

        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);
        Map<String, Boolean> allowed = new LinkedHashMap<>();
        for (String role : L1.keySet()) {
            allowed.put(role, enforcer.check(tool, Map.of("userId", idOf.get(role), "userRole", role,
                    "factoryId", F)).isAllowed());
        }
        assertThat(allowed).containsEntry("factory_super_admin", true)
                .containsEntry("finance_manager", true)
                .containsEntry("department_admin", true)
                .containsEntry("production_manager", false)
                .containsEntry("sales_manager", false)
                .containsEntry("procurement_manager", false);
    }

    /** report_finance 的结果, 数与线上 (acc-s6 s610j.log) 一致。 */
    private static IntentExecuteResponse financeResult() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalRevenue", new BigDecimal("100.00"));
        data.put("totalCost", new BigDecimal("3.00"));
        data.put("equipmentCost", new BigDecimal("2.00"));
        data.put("otherCost", new BigDecimal("1.00"));
        data.put("totalProfit", new BigDecimal("-3.00"));
        data.put("materialCost", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportType", "FINANCE");
        result.put("period", "month");
        result.put("startDate", "2026-10-04");
        result.put("endDate", "2026-11-03");
        result.put("data", data);
        IntentExecuteResponse r = new IntentExecuteResponse();
        r.setIntentRecognized(true);
        r.setIntentCode("REPORT_FINANCE");
        r.setIntentName("财务报表");
        r.setIntentCategory("REPORT");
        r.setStatus("SUCCESS");
        r.setMessage("操作已完成。");
        r.setResultData(result);
        return r;
    }
}
