package com.cretas.aims.service.impl;

import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.PythonIntentMatchRequest;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import com.cretas.aims.service.intent.LegacyIntentExitMetrics;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INTENT-DISTILL-20261111: {@link AIIntentServiceImpl#recognizeIntentWithConfidence} 的两个旧出口
 * (指定意图的早期短语、Python 没给结果时的 Java 旧管线) 命中一次计数 +1, 返回结果与原测试口径一致。
 *
 * <p>走真实入口方法, 只在 Python HTTP 客户端 / 旧管线 / 缓存这一层打桩。
 */
@DisplayName("旧出口计数 — Java 识别入口接线")
class LegacyIntentExitWiringContractTest {

    private static final String F = "F-EXIT";

    private IntentRecognitionPipelineService pipeline;
    private PythonAiMatcherClient python;
    private IntentResultCache cache;
    private IntentAccessModeFilter accessModeFilter;
    private SimpleMeterRegistry registry;
    private AIIntentServiceImpl service;

    @BeforeEach
    void setUp() {
        pipeline = mock(IntentRecognitionPipelineService.class);
        python = mock(PythonAiMatcherClient.class);
        cache = mock(IntentResultCache.class);
        accessModeFilter = mock(IntentAccessModeFilter.class);
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(anyString())).thenReturn("FACTORY");
        when(pipeline.recognizeExpectedEarlyPhrase(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        service = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(service, "pythonClient", python);
        ReflectionTestUtils.setField(service, "intentResultCache", cache);
        ReflectionTestUtils.setField(service, "usePythonMatcher", true);
        ReflectionTestUtils.setField(service, "accessModeFilter", accessModeFilter);
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "legacyExitMetrics",
                new LegacyIntentExitMetrics(registry, 900, 20, 0.05, 0.20));
    }

    private double hit(String site, String exit, String rw, String reason) {
        Counter c = registry.find("intent.legacy.exit.hit")
                .tags("site", site, "exit", exit, "factory", F, "rw", rw, "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private double site(String site) {
        Counter c = registry.find("intent.legacy.exit.site.requests").tags("site", site).counter();
        return c == null ? 0 : c.count();
    }

    private static IntentMatchResult match(String code, IntentMatchResult.MatchMethod method) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code);
        cfg.setIntentName(code);
        return IntentMatchResult.builder().bestMatch(cfg).confidence(0.96).matchMethod(method).userInput("q").build();
    }

    private IntentMatchResult recognize(String q) {
        return service.recognizeIntentWithConfidence(q, F, 3, 22L, "admin", null);
    }

    @Test
    void pythonEmpty_legacyKeywordAnswers_countedAsFaultFallback_resultUnchanged() {
        when(python.match(any(PythonIntentMatchRequest.class))).thenReturn(IntentMatchResult.empty("查订单"));
        when(pipeline.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(match("ORDER_LIST", IntentMatchResult.MatchMethod.KEYWORD));
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);

        IntentMatchResult r = recognize("查订单");

        assertThat(r.getBestMatch().getIntentCode()).isEqualTo("ORDER_LIST");
        assertThat(r.getMatchMethod()).isEqualTo(IntentMatchResult.MatchMethod.KEYWORD);
        assertThat(hit(LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE, "java_legacy_KEYWORD", "read", "python_empty"))
                .isEqualTo(1.0);
        assertThat(site(LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE)).isEqualTo(1.0);
    }

    @Test
    void pythonException_legacyRegexWriteIntent_reasonCarriesExceptionType() {
        when(python.match(any(PythonIntentMatchRequest.class))).thenThrow(new IllegalArgumentException("down"));
        when(pipeline.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(match("ORDER_CREATE", IntentMatchResult.MatchMethod.REGEX));
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(true);

        IntentMatchResult r = recognize("下个单");

        assertThat(r.getBestMatch().getIntentCode()).isEqualTo("ORDER_CREATE");
        assertThat(hit(LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE, "java_legacy_REGEX", "write",
                "python_exception_IllegalArgumentException")).isEqualTo(1.0);
    }

    @Test
    void pythonDisabled_legacyIsPrimary_countedWithNonFaultReason() {
        ReflectionTestUtils.setField(service, "usePythonMatcher", false);
        when(pipeline.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(match("ORDER_LIST", IntentMatchResult.MatchMethod.SEMANTIC));

        IntentMatchResult r = recognize("查订单");

        assertThat(r.getBestMatch().getIntentCode()).isEqualTo("ORDER_LIST");
        assertThat(hit(LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE, "java_legacy_SEMANTIC", "read",
                "python_disabled")).isEqualTo(1.0);
    }

    @Test
    void pythonAnswered_noLegacyExitCounted_onlyDenominator() {
        when(python.match(any(PythonIntentMatchRequest.class)))
                .thenReturn(match("ORDER_LIST", IntentMatchResult.MatchMethod.LLM));

        IntentMatchResult r = recognize("查订单");

        assertThat(r.getBestMatch().getIntentCode()).isEqualTo("ORDER_LIST");
        verify(pipeline, never()).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any());
        assertThat(registry.find("intent.legacy.exit.hit").counters()).isEmpty();
        assertThat(site(LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE)).isEqualTo(1.0);
        assertThat(site(LegacyIntentExitMetrics.SITE_RECOGNIZE_EARLY_PHRASE)).isEqualTo(1.0);
    }

    @Test
    void earlyPhrase_decidesBeforePython_counted_pythonNeverCalled() {
        when(pipeline.recognizeExpectedEarlyPhrase(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.of(match("QUALITY_CHECK_QUERY", IntentMatchResult.MatchMethod.PHRASE_MATCH)));
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);

        IntentMatchResult r = recognize("质检情况");

        assertThat(r.getBestMatch().getIntentCode()).isEqualTo("QUALITY_CHECK_QUERY");
        verify(python, never()).match(any());
        assertThat(hit(LegacyIntentExitMetrics.SITE_RECOGNIZE_EARLY_PHRASE, "java_early_phrase", "read",
                "precedes_decision")).isEqualTo(1.0);
        assertThat(site(LegacyIntentExitMetrics.SITE_RECOGNIZE_PIPELINE)).isEqualTo(0.0);
    }

    @Test
    void withoutMetricsBean_sameResult() {
        ReflectionTestUtils.setField(service, "legacyExitMetrics", null);
        when(python.match(any(PythonIntentMatchRequest.class))).thenReturn(IntentMatchResult.empty("查订单"));
        when(pipeline.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(match("ORDER_LIST", IntentMatchResult.MatchMethod.KEYWORD));

        IntentMatchResult r = recognize("查订单");

        assertThat(r.getBestMatch().getIntentCode()).isEqualTo("ORDER_LIST");
        verify(pipeline, times(1)).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any());
    }
}
