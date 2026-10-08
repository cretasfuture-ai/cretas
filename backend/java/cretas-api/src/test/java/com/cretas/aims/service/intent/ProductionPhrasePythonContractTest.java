package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.config.IntentMatchingConfig;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentMatchRecord;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import com.cretas.aims.service.intent.impl.IntentRecognitionPipelineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real nine-argument facade and pipeline; only external matcher/cache/storage/registry IO is stubbed. */
class ProductionPhrasePythonContractTest extends IntentRecognitionRoleRepickPermissionTest {
    private static final String TARGET = "REPORT_TRENDS";
    private static final String QUESTION = "这周产量环比怎么样";
    private static final String AUDIENCE = "factory_super_admin";
    private PythonAiMatcherClient python;
    private IntentResultCache cache;

    private AIIntentServiceImpl entry() { return (AIIntentServiceImpl) ReflectionTestUtils.getField(this, "facade"); }
    @SuppressWarnings("unchecked")
    private Map<String, AIIntentConfig> catalog() { return (Map<String, AIIntentConfig>) ReflectionTestUtils.getField(this, "catalog"); }
    private IntentConfigManagementServiceImpl configs() { return (IntentConfigManagementServiceImpl) ReflectionTestUtils.getField(this, "configService"); }
    private IntentRecognitionPipelineServiceImpl pipeline() { return (IntentRecognitionPipelineServiceImpl) ReflectionTestUtils.getField(entry(), "pipelineService"); }
    private IntentMatchResult recognize(String question, String role) {
        return entry().recognizeIntentWithConfidence(question, "F006", 5, 1309L, role, "python-contract", "READ", Set.of(), List.of());
    }
    private void addRead(String code, String toolName) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code); cfg.setIntentName(code); cfg.setToolName(toolName); cfg.setBusinessType("FACTORY");
        cfg.setIsActive(true); cfg.setPriority(0); cfg.setSensitivityLevel("LOW"); cfg.setKeywords("[]");
        cfg.setRequiredRoles(code.equals("REPORT_TRENDS") ? "[\"factory_super_admin\",\"department_admin\"]" : "[\"factory_super_admin\",\"quality_inspector\"]");
        catalog().put(code, cfg);
        IntentAccessModeFilter filter = (IntentAccessModeFilter) ReflectionTestUtils.getField(entry(), "accessModeFilter");
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(filter, "toolRegistry");
        ToolExecutor executor = mock(ToolExecutor.class); when(executor.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        when(registry.getExecutor(toolName)).thenReturn(Optional.of(executor));
    }
    private IntentMatchResult result(String code) {
        return IntentMatchResult.builder().bestMatch(catalog().get(code)).confidence(.91)
            .matchMethod(IntentMatchResult.MatchMethod.LLM).requiresConfirmation(false).build();
    }
    private void enabled() {
        addRead("REPORT_TRENDS", "report_trends"); addRead("QUALITY_STATS", "quality_stats");
        python = mock(PythonAiMatcherClient.class); cache = mock(IntentResultCache.class);
        ReflectionTestUtils.setField(entry(), "pythonClient", python);
        ReflectionTestUtils.setField(entry(), "intentResultCache", cache);
        ReflectionTestUtils.setField(entry(), "usePythonMatcher", true);
        ReflectionTestUtils.setField(entry(), "pythonMatcherFactories", "");
        ReflectionTestUtils.setField(pipeline(), "queryPreprocessorService", new QueryPreprocessorServiceImpl(null, null, null, null, new ObjectMapper(), null, null));
        when(cache.get(anyString(), eq("F006"), anyString(), anyString())).thenReturn(result("REPORT_INVENTORY"));
        when(python.match(any())).thenReturn(result("REPORT_INVENTORY"));
    }
    private void assertPhrase(IntentMatchResult got) {
        assertThat(got.hasMatch()).isTrue(); assertThat(got.getBestMatch().getIntentCode()).isEqualTo(TARGET);
        assertThat(got.getMatchMethod()).isEqualTo(IntentMatchResult.MatchMethod.PHRASE_MATCH);
        assertThat(got.getConfidence()).isEqualTo(.96); assertThat(got.getRequiresConfirmation()).isFalse();
        assertThat(got.getMatchedKeywords()).doesNotContain(TARGET); // Never invent a matched keyword from an intent code.
    }
    @Test void authoritativePhraseWinsBeforeAnAvailableWrongCachedCandidate() {
        enabled(); assertPhrase(recognize(QUESTION, "factory_super_admin")); verifyNoInteractions(cache, python);
    }
    @Test void allFourExplicitProductionComparisonsUseTheSameRealPreflight() {
        enabled();
        for (String question : List.of("这周产量环比怎么样", "这个月产量同比", "产量比上周怎么样", "今年产量比去年怎么样")) {
            assertPhrase(recognize(question, AUDIENCE));
        }
        verifyNoInteractions(cache, python);
    }
    @Test void authoritativePhraseWinsBeforeAnAvailableWrongPythonCandidate() {
        enabled(); when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        assertPhrase(recognize(QUESTION, "factory_super_admin")); verifyNoInteractions(cache, python);
    }
    @Test void intendedAudienceStillGetsTheRealPhraseWithBothExternalBranchesEnabled() {
        enabled(); assertPhrase(recognize(QUESTION, AUDIENCE)); verifyNoInteractions(cache, python);
    }
    @Test void nonTargetControlActuallyUsesCacheAndKeepsItsOriginalDecision() {
        enabled(); IntentMatchResult got = recognize("产量怎么样", "factory_super_admin");
        assertThat(got.getBestMatch().getIntentCode()).isEqualTo("REPORT_INVENTORY");
        verify(cache).get(eq("产量怎么样"), eq("F006"), eq("factory_super_admin"), eq("FACTORY")); verifyNoInteractions(python);
    }
    @Test void cacheMissControlActuallyCallsPythonAndWritesItsCache() {
        enabled(); when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        IntentMatchResult got = recognize("产量怎么样", "factory_super_admin");
        assertThat(got.getBestMatch().getIntentCode()).isEqualTo("REPORT_INVENTORY");
        verify(python).match(argThat(request -> request.getQuery().equals("产量怎么样") && request.getUserId().equals("1309") && request.getFactoryId().equals("F006")));
        verify(cache).put(eq("产量怎么样"), eq("F006"), eq("factory_super_admin"), eq("FACTORY"), any());
    }
    @Test void qualityStatisticsAreNotForcedIntoTheNewRoute() {
        enabled(); when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(result("QUALITY_STATS"));
        assertThat(recognize("查一下最近的质检统计", "quality_inspector").getBestMatch().getIntentCode()).isEqualTo("QUALITY_STATS");
        verify(cache).get(eq("查一下最近的质检统计"), eq("F006"), eq("quality_inspector"), eq("FACTORY"));
    }
    @Test void negatedTargetFallsThroughToTheOriginalExternalBranch() {
        enabled(); recognize("不要查询" + QUESTION, "factory_super_admin");
        verify(cache).get(eq("不要查询" + QUESTION), eq("F006"), eq("factory_super_admin"), eq("FACTORY"));
    }
    @Test void sanitizedTargetUsesTheSameGuardedPhraseBuilder() {
        enabled(); IntentMatchResult got = recognize("<b>" + QUESTION + "</b>", "factory_super_admin");
        assertPhrase(got); assertThat(got.getUserInput()).isEqualTo(QUESTION); verifyNoInteractions(cache, python);
    }
    @Test void inactiveDeletedAndWrongDomainConfigsNeverUseThePreflight() {
        enabled(); AIIntentConfig cfg = catalog().get(TARGET);
        cfg.setIsActive(false); recognize(QUESTION, "factory_super_admin"); verify(cache).get(anyString(), anyString(), anyString(), anyString());
        clearInvocations(cache); cfg.setIsActive(true); cfg.setDeletedAt(LocalDateTime.now());
        recognize(QUESTION, "factory_super_admin"); verify(cache).get(anyString(), anyString(), anyString(), anyString());
        clearInvocations(cache); cfg.setDeletedAt(null); cfg.setBusinessType("RESTAURANT");
        recognize(QUESTION, "factory_super_admin"); verify(cache).get(anyString(), anyString(), anyString(), anyString());
    }
    @Test void restaurantAndUnknownDomainFallThroughWithoutInventingFactoryMatches() {
        enabled(); IntentConfigManagementServiceImpl spy = spy(configs());
        ReflectionTestUtils.setField(entry(), "configService", spy); ReflectionTestUtils.setField(pipeline(), "configService", spy);
        doReturn("RESTAURANT").when(spy).resolveBusinessDomain("F006");
        recognize(QUESTION, "factory_super_admin"); verify(cache).get(anyString(), eq("F006"), anyString(), eq("RESTAURANT"));
        clearInvocations(cache); doReturn(null).when(spy).resolveBusinessDomain("F006");
        recognize(QUESTION, "factory_super_admin"); verify(cache).get(anyString(), eq("F006"), anyString(), eq(""));
    }
    @Test void warehouseStillHasNoPermissionWhileTheIntendedAudienceDoes() {
        enabled(); assertThat(configs().hasPermission(TARGET, "warehouse_worker")).isFalse();
        assertThat(configs().hasPermission(TARGET, AUDIENCE)).isTrue();
        recognize(QUESTION, "warehouse_worker"); verifyNoInteractions(cache, python);
    }
    @Test void theNormalEarlyPhraseKeepsItsOriginalDownstreamResultShape() {
        enabled();
        IntentMatchResult normal = pipeline().recognizeIntentWithConfidence("查库存", "F006", 5, 1309L, "factory_super_admin", "normal-shape");
        assertThat(normal.getBestMatch().getIntentCode()).isEqualTo("REPORT_INVENTORY");
        assertThat(normal.getConfidence()).isEqualTo(.96);
        assertThat(normal.getTopCandidates()).isNull(); assertThat(normal.getMatchedKeywords()).isNull();
        assertThat(normal.getIsStrongSignal()).isNull(); assertThat(normal.getRequiresConfirmation()).isNull();
    }
    @Test void excludingContentIsNotMistakenForAVetoOfTheReadCommand() {
        enabled(); String question = QUESTION + "，排除不合格的";
        QueryPreprocessorServiceImpl preprocessor = (QueryPreprocessorServiceImpl) ReflectionTestUtils.getField(pipeline(), "queryPreprocessorService");
        assertThat(preprocessor.detectNegationSemantics(question, (com.cretas.aims.config.IntentKnowledgeBase) ReflectionTestUtils.getField(pipeline(), "knowledgeBase")).getKind())
            .isEqualTo(com.cretas.aims.service.QueryPreprocessorService.NegationKind.EXCLUDE_CONTENT);
        assertPhrase(recognize(question, AUDIENCE)); verifyNoInteractions(cache, python);
    }
    @Test void theEarlyHitIsAuditedOnceAndAnExternalFallthroughDoesNotDoubleAudit() {
        enabled(); IntentMatchingConfig matching = (IntentMatchingConfig) ReflectionTestUtils.getField(pipeline(), "matchingConfig");
        when(matching.isRecordingEnabled()).thenReturn(true);
        IntentMatchRecordRepository records = mock(IntentMatchRecordRepository.class);
        ReflectionTestUtils.setField(pipeline(), "recordRepository", records); ReflectionTestUtils.setField(pipeline(), "objectMapper", new ObjectMapper());
        assertPhrase(recognize(QUESTION, "factory_super_admin")); verify(records, times(1)).save(any(IntentMatchRecord.class));
        clearInvocations(records); recognize("产量怎么样", "factory_super_admin"); verifyNoInteractions(records);
    }
}
