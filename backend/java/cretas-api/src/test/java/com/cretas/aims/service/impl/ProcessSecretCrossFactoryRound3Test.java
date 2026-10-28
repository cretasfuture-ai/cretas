package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.UpdateIntentTool;
import com.cretas.aims.controller.AIIntentConfigController;
import com.cretas.aims.controller.IntentAnalysisController;
import com.cretas.aims.dto.smartbi.FieldMappingWithChartRole;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentOptimizationSuggestion;
import com.cretas.aims.entity.learning.LearningSuggestion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.IntentOptimizationSuggestionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.learning.LearningSuggestionRepository;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.intent.IntentConfigWriteGuard;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.smartbi.impl.LLMFieldMappingServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 第三轮复审 (2026-10-29): 意图配置写入 / 平台晋升 / AI 改意图 / 学习建议 / 字段映射 / 意图名兜底 都只认
 * 服务端认定的工厂; 平台级内容只给平台账号且不许提到任何一家工厂的工艺保密产品名。每组带阴性对照。
 */
class ProcessSecretCrossFactoryRound3Test {

    static final String FA = "FA";
    static final String FB = "FB";
    static final String SECRET = "秘制酱牛肉";

    ProcessSecretSampleFilter filter;

    @BeforeEach
    void setUp() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        when(access.factoriesWithProtectedProducts()).thenReturn(Set.of(FA));
        when(access.protectedProductTypeIds(FA)).thenReturn(Set.of("pA"));
        ProductType p = new ProductType();
        p.setId("pA");
        p.setFactoryId(FA);
        p.setName(SECRET);
        when(products.findAllById(Set.of("pA"))).thenReturn(List.of(p));
        filter = new ProcessSecretSampleFilter(access, products);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    static Optional<AuthenticatedActor> factoryAdmin() {
        return Optional.of(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 7L, FA, "factory_super_admin"));
    }

    static Optional<AuthenticatedActor> platformAdmin() {
        return Optional.of(new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, null, "super_admin"));
    }

    static void bind(Optional<AuthenticatedActor> actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        actor.ifPresent(a -> request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, a));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    static AIIntentConfig body(String factoryId, String id, String keywords) {
        AIIntentConfig c = new AIIntentConfig();
        c.setId(id);
        c.setFactoryId(factoryId);
        c.setIntentCode("PRODUCTION_QUERY");
        c.setIntentName("产量");
        c.setKeywords(keywords);
        c.setDescription("查产量");
        return c;
    }

    // ---------------------------------------------------------------- H-A 意图配置写入

    @Nested
    class IntentConfigWrites {
        AIIntentConfigRepository repo;
        IntentConfigWriteGuard guard;

        @BeforeEach
        void setUp() {
            repo = mock(AIIntentConfigRepository.class);
            guard = new IntentConfigWriteGuard(repo, filter);
        }

        @Test
        void factoryAdminCreateIgnoresBodyFactoryAndId() {
            AIIntentConfig row = guard.prepareCreate(FA, body(null, "platform-row-id", "[\"产量\"]"), factoryAdmin());
            assertThat(row.getFactoryId()).isEqualTo(FA);
            assertThat(row.getId()).isNull();
            assertThat(row.getKeywords()).isEqualTo("[\"产量\"]");
        }

        @Test
        void factoryAdminCannotCreateOrUpdatePlatformRows() {
            assertThatThrownBy(() -> guard.prepareCreate("PLATFORM", body(null, null, "[]"), factoryAdmin()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            assertThatThrownBy(() -> guard.prepareUpdate("PLATFORM", "PRODUCTION_QUERY", body(null, null, "[]"),
                    factoryAdmin())).isInstanceOf(BusinessException.class);
            verify(repo, never()).findByFactoryIdAndIntentCode(any(), anyString());
        }

        @Test
        void platformRowsNamingAnyFactorysProtectedProductAreRefused() {
            assertThatThrownBy(() -> guard.prepareCreate("PLATFORM", body(null, null, "[\"" + SECRET + "\"]"),
                    platformAdmin()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
            // 阴性对照: 平台账号写不提保密产品的平台级行 → 照常 (factory_id 为空)
            assertThat(guard.prepareCreate("PLATFORM", body(FA, null, "[\"产量\"]"), platformAdmin()).getFactoryId())
                    .isNull();
        }

        @Test
        void updateLoadsTheOwnRowAndCopiesOnlyBusinessFields() {
            AIIntentConfig own = body(FA, "own-id", "[\"产量\"]");
            when(repo.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(own));
            AIIntentConfig incoming = body(null, "platform-row-id", "[\"产量\",\"出货\"]");
            incoming.setDescription("新描述");

            AIIntentConfig saved = guard.prepareUpdate(FA, "PRODUCTION_QUERY", incoming, factoryAdmin());

            assertThat(saved).isSameAs(own);
            assertThat(saved.getId()).isEqualTo("own-id");
            assertThat(saved.getFactoryId()).isEqualTo(FA);
            assertThat(saved.getDescription()).isEqualTo("新描述");
        }

        @Test
        void updateNeverLandsOnAPlatformOrForeignRow() {
            when(repo.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(body(null, "p", "[]")));
            assertThatThrownBy(() -> guard.prepareUpdate(FA, "PRODUCTION_QUERY", body(null, null, "[]"), factoryAdmin()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
            when(repo.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(body(FB, "b", "[]")));
            assertThatThrownBy(() -> guard.prepareUpdate(FA, "PRODUCTION_QUERY", body(null, null, "[]"), factoryAdmin()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        void controllerCreatePassesTheGuardedRowToTheService() {
            AIIntentService service = mock(AIIntentService.class);
            when(service.createIntent(any())).thenAnswer(inv -> inv.getArgument(0));
            AIIntentConfigController controller = new AIIntentConfigController(service,
                    mock(com.cretas.aims.service.IntentExecutorService.class),
                    mock(com.cretas.aims.service.KeywordEffectivenessService.class),
                    mock(com.cretas.aims.service.impl.IntentConfigRollbackService.class),
                    mock(com.cretas.aims.service.ParameterExtractionLearningService.class),
                    mock(com.cretas.aims.utils.JwtUtil.class),
                    mock(com.cretas.aims.service.intent.IntentConfigManagementService.class));
            ReflectionTestUtils.setField(controller, "intentConfigWriteGuard", guard);
            bind(factoryAdmin());

            controller.createIntent(FA, body(null, "platform-row-id", "[\"产量\"]"));

            ArgumentCaptor<AIIntentConfig> saved = ArgumentCaptor.forClass(AIIntentConfig.class);
            verify(service).createIntent(saved.capture());
            assertThat(saved.getValue().getFactoryId()).isEqualTo(FA);
            assertThat(saved.getValue().getId()).isNull();
        }
    }

    // ---------------------------------------------------------------- H-B 平台晋升列表 / 审批

    @Nested
    class PlatformPromotionAccess {
        IntentOptimizationSuggestionRepository suggestions;
        AIIntentConfigRepository intents;
        IntentAnalysisController controller;

        @BeforeEach
        void setUp() {
            suggestions = mock(IntentOptimizationSuggestionRepository.class);
            intents = mock(AIIntentConfigRepository.class);
            controller = new IntentAnalysisController(
                    mock(com.cretas.aims.service.ErrorAttributionAnalysisService.class),
                    mock(com.cretas.aims.scheduler.ErrorAttributionAnalysisScheduler.class),
                    mock(com.cretas.aims.repository.ErrorAttributionStatisticsRepository.class),
                    mock(IntentMatchRecordRepository.class), suggestions, intents,
                    mock(com.cretas.aims.service.SemanticRouterService.class), filter);
        }

        IntentAnalysisController.ApprovalRequest approve() {
            IntentAnalysisController.ApprovalRequest req = new IntentAnalysisController.ApprovalRequest();
            req.setApproved(true);
            return req;
        }

        @Test
        void factoryAccountsCannotListOrApprovePlatformPromotions() {
            bind(factoryAdmin());
            assertThatThrownBy(() -> controller.getPendingPromotions(FA))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            assertThatThrownBy(() -> controller.approvePromotion(FA, "s1", approve()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            verifyNoInteractions(suggestions, intents);
        }

        @Test
        @SuppressWarnings("unchecked")
        void platformApprovalResponseCarriesNoIntentBody() {
            bind(platformAdmin());
            when(suggestions.findById("s1")).thenReturn(Optional.of(IntentOptimizationSuggestion
                    .createPromotionSuggestion(FA, "PRODUCTION_QUERY", "产量", "通用", "admin")));
            when(intents.findByFactoryIdAndIntentCode(FA, "PRODUCTION_QUERY"))
                    .thenReturn(Optional.of(body(FA, "own", "[\"产量\"]")));

            Map<String, Object> data = controller.approvePromotion("PLATFORM", "s1", approve()).getBody().getData();

            assertThat(data).containsEntry("status", "APPROVED").containsEntry("intentCode", "PRODUCTION_QUERY")
                    .doesNotContainKey("intent");
            assertThat(controller.getPendingPromotions("PLATFORM").getBody().getSuccess()).isTrue();
        }
    }

    // ---------------------------------------------------------------- M-A AI 改意图工具

    @Test
    void updateIntentToolOnlyEditsTheCallersOwnRow() throws Exception {
        AIIntentService service = mock(AIIntentService.class);
        UpdateIntentTool tool = new UpdateIntentTool();
        ReflectionTestUtils.setField(tool, "aiIntentService", service);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        ToolCall call = ToolCall.builder().id("c1").function(ToolCall.FunctionCall.builder().name("update_intent")
                .arguments("{\"intentCode\":\"PRODUCTION_QUERY\",\"description\":\"改了\"}").build()).build();
        Map<String, Object> context = Map.of("factoryId", FA, "userId", 7L);

        // getIntentByCode 退回平台级行 → 不改
        when(service.getIntentByCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(body(null, "p", "[]")));
        tool.execute(call, context);
        verify(service, never()).updateIntent(any());

        // 阴性对照: 本厂行照常改
        when(service.getIntentByCode(FA, "PRODUCTION_QUERY")).thenReturn(Optional.of(body(FA, "own", "[]")));
        when(service.updateIntent(any())).thenAnswer(inv -> inv.getArgument(0));
        tool.execute(call, context);
        verify(service).updateIntent(any());
    }

    // ---------------------------------------------------------------- M-B 学习建议按工厂取

    @Test
    void learningSuggestionsAreLookedUpByIdAndUrlFactory() {
        ActiveLearningServiceImpl service = Mockito.mock(ActiveLearningServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        LearningSuggestionRepository repo = mock(LearningSuggestionRepository.class);
        ReflectionTestUtils.setField(service, "suggestionRepository", repo);
        LearningSuggestion foreign = LearningSuggestion.builder().factoryId(FB).intentCode("I").content("x")
                .suggestionType(LearningSuggestion.SuggestionType.NEW_EXPRESSION).build();
        when(repo.findById(5L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.approveSuggestion(FA, 5L, "r", "n")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.rejectSuggestion(FA, 5L, "r", "n")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.applySuggestion(FA, 5L, "r")).isInstanceOf(BusinessException.class);
        verify(repo, never()).approveSuggestion(any(), any(), any(), any());
        verify(repo, never()).rejectSuggestion(any(), any(), any(), any());

        // 阴性对照: 本厂的建议照常审批
        service.approveSuggestion(FB, 5L, "r", "n");
        verify(repo).approveSuggestion(eq(5L), eq("r"), any(), eq("n"));
    }

    // ---------------------------------------------------------------- L1 字段映射不写全局

    @Test
    void autoFieldMappingWithoutFactoryIsNeverWrittenAsGlobal() {
        LLMFieldMappingServiceImpl service = new LLMFieldMappingServiceImpl();
        SmartBiDictionaryRepository dictionary = mock(SmartBiDictionaryRepository.class);
        ReflectionTestUtils.setField(service, "dictionaryRepository", dictionary);
        FieldMappingWithChartRole mapping = new FieldMappingWithChartRole();
        mapping.setStandardField("product_name");
        mapping.setOriginalField(SECRET);
        mapping.setAlias("产品");
        mapping.setRole(FieldMappingWithChartRole.FieldRole.DIMENSION);
        mapping.setChartAxis(FieldMappingWithChartRole.ChartAxisRole.X_AXIS);

        ReflectionTestUtils.invokeMethod(service, "saveToDatabase", mapping, null);
        ReflectionTestUtils.invokeMethod(service, "saveToDatabase", mapping, " ");
        verifyNoInteractions(dictionary);

        // 阴性对照: 有工厂照常查本厂字典行
        ReflectionTestUtils.invokeMethod(service, "saveToDatabase", mapping, FA);
        verify(dictionary).findByDictTypeAndNameAndFactoryId(anyString(), eq("product_name"), eq(FA));
    }

    // ---------------------------------------------------------------- L2 意图名兜底只取平台级行

    @Test
    void intentNameFallbackReadsOnlyThePlatformRow() {
        AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
        AIIntentConfig foreign = body(FB, "b", "[]");
        foreign.setDescription(SECRET + "专用");
        when(intents.findByIntentCode("PRODUCTION_QUERY")).thenReturn(Optional.of(foreign));
        when(intents.findByFactoryIdAndIntentCode(null, "PRODUCTION_QUERY")).thenReturn(Optional.empty());
        RAGRetrievalServiceImpl rag = new RAGRetrievalServiceImpl(mock(IntentMatchRecordRepository.class),
                mock(com.cretas.aims.repository.learning.LearnedExpressionRepository.class), intents,
                mock(com.cretas.aims.service.EmbeddingClient.class),
                mock(com.cretas.aims.service.IntentEmbeddingCacheService.class),
                mock(com.cretas.aims.service.RequestScopedEmbeddingCache.class));

        String name = ReflectionTestUtils.invokeMethod(rag, "getIntentName", FA, "PRODUCTION_QUERY");
        assertThat(name).isEqualTo("PRODUCTION_QUERY").doesNotContain(SECRET);
        verify(intents, never()).findByIntentCode(anyString());

        AIIntentConfig platform = body(null, "p", "[]");
        platform.setDescription("平台描述");
        when(intents.findByFactoryIdAndIntentCode(null, "PRODUCTION_QUERY")).thenReturn(Optional.of(platform));
        assertThat((String) ReflectionTestUtils.invokeMethod(rag, "getIntentName", FA, "PRODUCTION_QUERY"))
                .isEqualTo("平台描述");
    }
}
