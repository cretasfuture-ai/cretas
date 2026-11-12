package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.intent.LegacyIntentExitMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INTENT-DISTILL-20261111: {@link IntentExecutionOrchestrator#execute} 的短语直达 (子串匹配, 不经决策模型)
 * 命中一次计数 +1, 行为不变 —— 命中时照旧跳过识别直接按显式意图走, 不命中时照旧进识别。
 */
@DisplayName("旧出口计数 — 执行入口短语直达")
class PhraseShortcutExitMetricsContractTest {

    private static final String FACTORY = "F006";
    private static final long USER = 1309L;
    private static final String ROLE = "factory_super_admin";

    private AIIntentService aiIntentService;
    private IntentKnowledgeBase knowledgeBase;
    private IntentExecutionOrchestrator orchestrator;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        knowledgeBase = mock(IntentKnowledgeBase.class);
        SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService, mock(IntentSemanticsParser.class), semanticCache, mock(RuleEngineService.class),
                mock(ConversationService.class), mock(ConversationMemoryService.class), new ObjectMapper(),
                mock(DashScopeClient.class), mock(DashScopeConfig.class), knowledgeBase,
                mock(AIAnalysisResultRepository.class), mock(ToolRegistry.class), mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class), mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class), mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(orchestrator, "legacyExitMetrics",
                new LegacyIntentExitMetrics(registry, 900, 20, 0.05, 0.20));
    }

    private void execute(String input) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
            orchestrator.execute(FACTORY, IntentExecuteRequest.builder().userInput(input).build(), USER, ROLE);
        }
    }

    private double hit(String rw) {
        Counter c = registry.find("intent.legacy.exit.hit")
                .tags("site", LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE, "exit", "java_phrase_shortcut",
                        "factory", FACTORY, "rw", rw, "reason", "precedes_decision").counter();
        return c == null ? 0 : c.count();
    }

    private double requests() {
        Counter c = registry.find("intent.legacy.exit.site.requests")
                .tags("site", LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void phraseHit_isCounted_andStillSkipsRecognition() {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode("MATERIAL_BATCH_QUERY");
        cfg.setIntentName("原料批次查询");
        cfg.setSensitivityLevel("LOW");
        cfg.setIsActive(true);
        when(knowledgeBase.matchPhrase(eq("查原料批次"), anyString())).thenReturn(Optional.of("MATERIAL_BATCH_QUERY"));
        when(aiIntentService.getIntentByCode(eq(FACTORY), eq("MATERIAL_BATCH_QUERY"))).thenReturn(Optional.of(cfg));

        execute("查原料批次");

        assertThat(hit("read")).isEqualTo(1.0);
        assertThat(requests()).isEqualTo(1.0);
        // 行为不变: 短语直达接管, 识别层一次都没被问
        verify(aiIntentService, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void phraseMiss_countsOnlyTheDenominator_andRecognitionStillRuns() {
        when(knowledgeBase.matchPhrase(anyString(), anyString())).thenReturn(Optional.empty());
        when(aiIntentService.recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(IntentMatchResult.empty("随便说一句"));

        execute("随便说一句");

        assertThat(hit("read") + hit("write") + hit("unknown") + hit("none")).isZero();
        assertThat(requests()).isEqualTo(1.0);
        verify(aiIntentService, atLeastOnce()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), any(), any(), any(), any());
    }
}
