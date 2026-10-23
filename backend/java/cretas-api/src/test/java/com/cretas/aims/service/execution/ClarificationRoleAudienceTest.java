package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.FrontlineReadAudience;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 澄清选项按角色过滤 (2026-10-07 质检员真测): 质检员说「今天待质检的批次」, 选项里出现了仓库员收货用的「今日待收货清单」。
 *
 * <p>成因不在过滤器 —— 权限码 / required_roles / 一线写开放名单它都过了 —— 而在于这两个读意图在库里
 * {@code required_roles = NULL} (对所有角色放行), 过滤器看的名单是空的。{@link com.cretas.aims.service.intent.FrontlineReadAudience}
 * 给一线角色再收一次。这里守两层: 选项构造器本身 (角色 × 意图矩阵), 与真实入口 SSE 流 (QI 角色一路走到 NEED_CLARIFICATION 文案)。
 *
 * <p>每条「被拿掉」的断言都配「没被拿掉」的对照: 有权限的角色 (仓库员) 两个选项都在、非一线角色不受影响、
 * 名单外的读意图 (质检统计 / 质量报表) 对质检员照旧给。
 */
class ClarificationRoleAudienceTest {

    private static final String FACTORY = "F006";
    private static final String QC_TODAY = "RECEIVE_QUALITY_CHECK_TODAY";
    private static final String RECEIVING = "MATERIAL_TODAY_RECEIVING_QUERY";

    private IntentConfigManagementService configService;
    private IntentPermissionGate permissionGate;
    private IntentAccessModeFilter accessModeFilter;
    private FrontlineAiWriteGrantService grants;
    private ClarificationCandidateService service;

    @BeforeEach
    void setUp() {
        configService = mock(IntentConfigManagementService.class);
        permissionGate = mock(IntentPermissionGate.class);
        accessModeFilter = mock(IntentAccessModeFilter.class);
        grants = mock(FrontlineAiWriteGrantService.class);
        // 读意图不受一线写开放名单影响 (denyReason 对读意图本来就是空) —— 与生产同口径
        when(grants.denyReason(any(), any(), any(), any())).thenReturn(Optional.empty());
        service = new ClarificationCandidateService(
                configService, permissionGate, accessModeFilter, new WriteGuardService(), grants, mock(ToolRegistry.class));
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        when(configService.hasPermission(eq(FACTORY), anyString(), anyString())).thenReturn(true);   // required_roles 空 = 放行
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
        for (String[] c : new String[][]{
                {QC_TODAY, "今日待质检批次", "COMMON"}, {RECEIVING, "今日待收货清单", "COMMON"},
                {"QUALITY_STATS", "质检统计", "FACTORY"}, {"REPORT_QUALITY", "质量报表", "FACTORY"}}) {
            AIIntentConfig config = AIIntentConfig.builder().intentCode(c[0]).intentName(c[1]).businessType(c[2])
                    .intentCategory("QUERY").sensitivityLevel("LOW").build();
            config.setIsActive(true);
            when(configService.getIntentByCode(FACTORY, c[0])).thenReturn(Optional.of(config));
        }
    }

    private static IntentMatchResult candidates(String... codes) {
        return IntentMatchResult.builder().topCandidates(java.util.Arrays.stream(codes)
                .map(code -> IntentMatchResult.CandidateIntent.builder()
                        .intentCode(code).intentName("untrusted " + code).confidence(0.7).build())
                .toList()).build();
    }

    private List<String> offered(String role, String... codes) {
        return service.buildCandidateActions(candidates(codes), FACTORY, 7L, role, "READ").stream()
                .filter(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .map(a -> String.valueOf(a.getParameters().get("intentCode")))
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"quality_inspector", "operator", "yield_operator"})
    void warehouseOnlyReadIntentIsNotOfferedToOtherFrontlineRoles(String role) {
        assertThat(offered(role, QC_TODAY, RECEIVING)).as(role).containsExactly(QC_TODAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"warehouse_worker", "factory_super_admin", "department_admin", "manager"})
    void controlRolesWithAudienceOrNotFrontlineStillSeeBothChoices(String role) {
        assertThat(offered(role, QC_TODAY, RECEIVING)).as(role).containsExactly(QC_TODAY, RECEIVING);
    }

    @Test
    void everyListedIntentIsDroppedForInspectorAndKeptForWarehouse() {
        // 参数化的那一维: 名单里的每一个意图码 (不只是这次看到的那个); 名单被清空这条就红
        assertThat(com.cretas.aims.service.intent.FrontlineReadAudience.listedIntentCodes())
                .contains(RECEIVING).hasSizeGreaterThanOrEqualTo(2);
        for (String code : com.cretas.aims.service.intent.FrontlineReadAudience.listedIntentCodes()) {
            when(configService.getIntentByCode(FACTORY, code)).thenReturn(Optional.of(config(code)));
            if ("QUALITY_CHECK_QUERY".equals(code)) {
                assertThat(FrontlineReadAudience.offeredTo(code, "quality_inspector")).as(code).isTrue();
                assertThat(FrontlineReadAudience.offeredTo(code, "warehouse_worker")).as(code).isFalse();
            } else {
                assertThat(offered("quality_inspector", QC_TODAY, code)).as(code).containsExactly(QC_TODAY);
                assertThat(offered("warehouse_worker", QC_TODAY, code)).as(code).containsExactly(QC_TODAY, code);
            }
        }
    }

    @Test
    void readIntentsOutsideTheAudienceListAreUntouchedForInspector() {
        // 对照: 质检统计 / 质量报表 对质检员照旧给 (过滤器只拿掉名单里的, 不是见一线角色就收紧)
        assertThat(offered("quality_inspector", "QUALITY_STATS", "REPORT_QUALITY"))
                .containsExactly("QUALITY_STATS", "REPORT_QUALITY");
    }

    @Test
    void qualityRecordsAreOnlyOfferedToQualityInspectorAmongFrontlineRoles() {
        AIIntentConfig qualityRecords = AIIntentConfig.builder().intentCode("QUALITY_CHECK_QUERY")
                .intentName("质检记录").businessType("FACTORY").intentCategory("QUERY").sensitivityLevel("LOW").build();
        qualityRecords.setIsActive(true);
        when(configService.getIntentByCode(FACTORY, "QUALITY_CHECK_QUERY")).thenReturn(Optional.of(qualityRecords));
        assertThat(offered("quality_inspector", "QUALITY_CHECK_QUERY", "QUALITY_STATS"))
                .containsExactly("QUALITY_CHECK_QUERY", "QUALITY_STATS");
        assertThat(offered("warehouse_worker", "QUALITY_CHECK_QUERY", "QUALITY_STATS")).containsExactly("QUALITY_STATS");
        assertThat(offered("factory_super_admin", "QUALITY_CHECK_QUERY", "QUALITY_STATS"))
                .containsExactly("QUALITY_CHECK_QUERY", "QUALITY_STATS");
        assertThat(FrontlineReadAudience.offeredTo("QUALITY_CHECK_QUERY", "quality_inspector")).isTrue();
        assertThat(FrontlineReadAudience.offeredTo("QUALITY_CHECK_QUERY", "warehouse_worker")).isFalse();
        assertThat(FrontlineReadAudience.offeredTo("QUALITY_CHECK_QUERY", "operator")).isFalse();
        assertThat(FrontlineReadAudience.offeredTo("QUALITY_CHECK_QUERY", "factory_super_admin")).isTrue();
    }

    @Test
    void whenTheListedChoiceIsTheOnlyCandidateTheInspectorGetsRephraseOnly() {
        // 不补假选项: 拿掉之后一个业务选项都不剩, 就只剩「重新描述」
        assertThat(service.buildCandidateActions(candidates(RECEIVING), FACTORY, 7L, "quality_inspector", "READ"))
                .extracting(IntentExecuteResponse.SuggestedAction::getActionCode).containsExactly("REPHRASE");
    }

    private static AIIntentConfig config(String code) {
        AIIntentConfig config = AIIntentConfig.builder().intentCode(code).intentName("名单内 " + code)
                .businessType("COMMON").intentCategory("QUERY").sensitivityLevel("LOW").build();
        config.setIsActive(true);
        return config;
    }

    // ==================== 真实入口: SSE 流 ====================

    @ParameterizedTest
    @ValueSource(strings = {"quality_inspector", "warehouse_worker"})
    void sseClarificationForTheSameSentenceDependsOnRole(String role) throws Exception {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        FactoryRepository factoryRepository = mock(FactoryRepository.class);
        Factory factory = new Factory();
        factory.setId(FACTORY);
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.of(factory));
        FactoryCapabilityPackRoutingPolicy packPolicy = new FactoryCapabilityPackRoutingPolicy(
                factoryRepository, new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), false);

        // 与 prod 日志同形: 识别选中「今日待质检批次」, 近邻候选里带着「今日待收货清单」, 要求确认
        AIIntentConfig best = AIIntentConfig.builder().intentCode(QC_TODAY).intentName("今日待质检批次")
                .intentCategory("QUERY").businessType("COMMON").sensitivityLevel("LOW").build();
        best.setIsActive(true);
        IntentMatchResult match = IntentMatchResult.builder()
                .bestMatch(best)
                .topCandidates(candidates(QC_TODAY, RECEIVING).getTopCandidates())
                .confidence(0.7457).matchMethod(IntentMatchResult.MatchMethod.LLM)
                .requiresConfirmation(true)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                .build();
        String input = "今天待质检的批次";
        when(semanticCache.queryCache(FACTORY, input)).thenReturn(com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
        when(knowledgeBase.detectQuestionType(input)).thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        when(aiIntentService.recognizeIntentWithConfidence(
                eq(input), eq(FACTORY), eq(3), eq(7L), eq(role), any(), eq("READ"), any(), any())).thenReturn(match);

        SseStreamingService sse = spy(new SseStreamingService(
                aiIntentService, semanticCache, knowledgeBase, mock(AnalysisRouterService.class), new ObjectMapper(),
                mock(ToolRegistry.class), mock(ToolDispatchService.class), mock(DynamicToolSelectionService.class),
                mock(DashScopeClient.class), mock(DashScopeConfig.class)));
        ReflectionTestUtils.setField(sse, "factoryCapabilityPackRoutingPolicy", packPolicy);
        ReflectionTestUtils.setField(sse, "businessTypeGate", mock(BusinessTypeGate.class));
        ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(sse, "clarificationCandidateService", service);

        List<Object[]> events = new ArrayList<>();
        doAnswer(invocation -> {
            events.add(new Object[]{invocation.getArgument(1), invocation.getArgument(2)});
            return null;
        }).when(sse).sendSseEvent(any(), anyString(), any());
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, FACTORY, role))) {
            ReflectionTestUtils.invokeMethod(sse, "executeStreamAsync", mock(SseEmitter.class), FACTORY,
                    IntentExecuteRequest.builder().userInput(input).mode("READ").build(),
                    7L, role, new F006BossQuestionClient.Cancellation());
        }

        IntentExecuteResponse result = events.stream().filter(e -> "result".equals(e[0]))
                .map(e -> (IntentExecuteResponse) e[1]).findFirst().orElseThrow();
        assertThat(result.getStatus()).as("必发生: 这一轮真的走到了澄清").isEqualTo("NEED_CLARIFICATION");
        List<String> names = result.getSuggestedActions().stream()
                .filter(a -> "SELECT_INTENT".equals(a.getActionCode()))
                .map(IntentExecuteResponse.SuggestedAction::getActionName).toList();
        if ("quality_inspector".equals(role)) {
            // 质检员: 只剩他要的那一个; 文案是单选项句式, 看不到仓库的清单
            assertThat(names).containsExactly("今日待质检批次");
            assertThat(result.getMessage()).contains("今日待质检批次").doesNotContain("今日待收货清单");
        } else {
            // 对照: 仓库员两个都在 (这个意图对他本来就开放), 说明上面是「按角色」拿掉的, 不是整个选项构造坏了
            assertThat(names).containsExactly("今日待质检批次", "今日待收货清单");
            assertThat(result.getMessage()).contains("今日待收货清单");
        }
    }
}
