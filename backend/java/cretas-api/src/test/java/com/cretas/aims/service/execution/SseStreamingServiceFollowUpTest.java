package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追问解读接在 App 实际走的流式入口上 (2026-10-04)。
 * <ul>
 *   <li>带 {@code context.lastAction} 的「好了」→ 一个确认按钮, <b>不识别、不执行</b>;</li>
 *   <li>对照: 同一句「好了」不带 lastAction → 照常识别;</li>
 *   <li>对照: 目标意图无权限 / 这句不在规则表里 → 照常识别;</li>
 *   <li>点了按钮回来 (显式意图码 + 按钮参数) → 走显式执行, 参数到得了工具; 不记学习样本。</li>
 * </ul>
 */
class SseStreamingServiceFollowUpTest {

    private static Map<String, Object> lastAction(String intentCode, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentCode", intentCode);
        m.put("params", params);
        m.put("at", System.currentTimeMillis() - 30_000L);
        return m;
    }

    @Test
    void doneAfterStartBecomesOneConfirmationButtonAndNothingRuns() throws Exception {
        Harness h = new Harness();
        h.recognizes("好了", "WORK_PROCESS_TASK_COMPLETE");

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("好了")
                .context(Map.of("lastAction", lastAction("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).build());

        assertThat(result.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(result.getIntentCode()).isEqualTo("WORK_PROCESS_TASK_COMPLETE");
        assertThat(result.getMessage()).contains("完成工序").contains("任务 12");
        assertThat(result.getMetadata()).containsEntry("followUpRule", FollowUpResolver.RULE_WP_DONE);
        assertThat(result.getSuggestedActions()).hasSize(1);
        IntentExecuteResponse.SuggestedAction button = result.getSuggestedActions().get(0);
        assertThat(button.getActionCode()).isEqualTo("SELECT_INTENT");
        assertThat(button.getParameters())
                .containsEntry("intentCode", "WORK_PROCESS_TASK_COMPLETE")
                .containsEntry("taskId", 12L)
                .containsEntry("followUpRule", FollowUpResolver.RULE_WP_DONE);
        verify(h.aiIntentService, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(h.toolDispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void withoutLastActionTheSameUtteranceIsRecognizedNormally() throws Exception {
        // 对照: 证明上一条的「没识别」来自 lastAction, 不是这句话被无条件拦下
        Harness h = new Harness();
        h.recognizes("好了", "WORK_PROCESS_TASK_COMPLETE");

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("好了").build());

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getMetadata()).isNull();
        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("好了"), eq("F001"), eq(3), eq(7L), eq("factory_super_admin"), any(), any(), any(), any());
    }

    @Test
    void utteranceOutsideTheRuleTableFallsThroughToRecognition() throws Exception {
        Harness h = new Harness();
        h.recognizes("今天产量多少", "WORK_PROCESS_TASK_COMPLETE");

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("今天产量多少")
                .context(Map.of("lastAction", lastAction("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).build());

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("今天产量多少"), eq("F001"), eq(3), eq(7L), eq("factory_super_admin"), any(), any(), any(), any());
    }

    @Test
    void noButtonForAnIntentTheRoleMayNotRun() throws Exception {
        Harness h = new Harness();
        h.recognizes("好了", "WORK_PROCESS_TASK_COMPLETE");
        when(h.aiIntentService.hasPermission("WORK_PROCESS_TASK_COMPLETE", "factory_super_admin")).thenReturn(false);

        h.execute(IntentExecuteRequest.builder().userInput("好了")
                .context(Map.of("lastAction", lastAction("WORK_PROCESS_TASK_START", Map.of("taskId", 12)))).build());

        verify(h.aiIntentService).recognizeIntentWithConfidence(
                eq("好了"), eq("F001"), eq(3), eq(7L), eq("factory_super_admin"), any(), any(), any(), any());
    }

    @Test
    void highSensitivityWriteAlsoOnlyGetsAButtonWithTheAdjustedQuantity() throws Exception {
        Harness h = new Harness();
        h.recognizes("再加十公斤", "MATERIAL_BATCH_USE");

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("再加十公斤")
                .context(Map.of("lastAction", lastAction("MATERIAL_BATCH_USE",
                        Map.of("batchId", "MB-7", "quantity", 20, "unit", "kg")))).build());

        assertThat(result.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(result.getSuggestedActions()).hasSize(1);
        assertThat(result.getSuggestedActions().get(0).getParameters())
                .containsEntry("intentCode", "MATERIAL_BATCH_USE")
                .containsEntry("batchId", "MB-7")
                .containsEntry("quantity", 30L);
        verify(h.toolDispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tappingTheButtonExecutesTheExplicitIntentWithTheButtonParamsAndRecordsNoSample() throws Exception {
        Harness h = new Harness();
        h.recognizes("好了", "WORK_PROCESS_TASK_COMPLETE");
        Map<String, Object> context = new HashMap<>();
        context.put("clarificationSelection", true);
        context.put("clarificationCandidates", List.of("WORK_PROCESS_TASK_COMPLETE"));
        context.put("taskId", 12L);
        context.put("followUpRule", FollowUpResolver.RULE_WP_DONE);
        context.put("lastAction", lastAction("WORK_PROCESS_TASK_START", Map.of("taskId", 12)));

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder().userInput("好了")
                .intentCode("WORK_PROCESS_TASK_COMPLETE").context(context).build());

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(any(), eq("F001"), sent.capture(), any(), eq(7L),
                eq("factory_super_admin"), any());
        assertThat(sent.getValue().getContext()).containsEntry("taskId", 12L);
        verify(h.aiIntentService, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(h.pythonAiMatcherClient, after(400).never())
                .recordSelection(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anOrdinaryClarificationPickIsStillRecordedAsASample() throws Exception {
        // 对照: 上一条的「不记样本」来自 followUpRule, 不是记样本这条路坏了
        Harness h = new Harness();
        h.recognizes("就是六十箱", "WORK_PROCESS_TASK_COMPLETE");

        h.execute(IntentExecuteRequest.builder().userInput("就是六十箱").intentCode("WORK_PROCESS_TASK_COMPLETE")
                .context(Map.of("clarificationSelection", true,
                        "clarificationCandidates", List.of("WORK_PROCESS_TASK_COMPLETE"))).build());

        verify(h.pythonAiMatcherClient, timeout(2000))
                .recordSelection(eq("F001"), eq(7L), eq("factory_super_admin"), eq("就是六十箱"),
                        eq("WORK_PROCESS_TASK_COMPLETE"), any());
    }

    private static final class Harness {
        private final AIIntentService aiIntentService = mock(AIIntentService.class);
        private final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final ToolDispatchService toolDispatchService = mock(ToolDispatchService.class);
        private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
        private final PythonAiMatcherClient pythonAiMatcherClient = mock(PythonAiMatcherClient.class);
        private final SseStreamingService service;
        private final SseEmitter emitter = mock(SseEmitter.class);

        private Harness() throws Exception {
            Factory factory = new Factory();
            factory.setId("F001");
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            FactoryCapabilityPackRoutingPolicy policy = new FactoryCapabilityPackRoutingPolicy(
                    factoryRepository,
                    new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()),
                    false);
            service = spy(new SseStreamingService(
                    aiIntentService, semanticCache, mock(IntentKnowledgeBase.class),
                    mock(AnalysisRouterService.class), new ObjectMapper(), toolRegistry, toolDispatchService,
                    mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", policy);
            ReflectionTestUtils.setField(service, "writeGuardService", mock(WriteGuardService.class));
            ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
            ReflectionTestUtils.setField(service, "pythonAiMatcherClient", pythonAiMatcherClient);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
        }

        private void recognizes(String input, String code) {
            String tool = code.toLowerCase();
            AIIntentConfig intent = AIIntentConfig.builder()
                    .intentCode(code).intentName("MATERIAL_BATCH_USE".equals(code) ? "原料领用" : "完成工序")
                    .intentCategory("PRODUCTION").businessType("FACTORY")
                    .sensitivityLevel("MATERIAL_BATCH_USE".equals(code) ? "HIGH" : "LOW").toolName(tool).build();
            when(aiIntentService.getIntentByCode("F001", code)).thenReturn(Optional.of(intent));
            when(aiIntentService.hasPermission(code, "factory_super_admin")).thenReturn(true);
            when(aiIntentService.recognizeIntentWithConfidence(
                    eq(input), eq("F001"), eq(3), eq(7L), eq("factory_super_admin"), any(), any(), any(), any()))
                    .thenReturn(IntentMatchResult.builder().userInput(input).bestMatch(intent).confidence(0.95)
                            .matchMethod(IntentMatchResult.MatchMethod.LLM).isStrongSignal(true)
                            .requiresConfirmation(false)
                            .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
            ToolExecutor executor = mock(ToolExecutor.class);
            when(toolRegistry.getExecutor(tool)).thenReturn(Optional.of(executor));
            when(toolDispatchService.executeWithTool(eq(executor), eq("F001"), any(), eq(intent), eq(7L),
                    eq("factory_super_admin"), any()))
                    .thenReturn(IntentExecuteResponse.builder().intentRecognized(true).intentCode(code)
                            .status("COMPLETED").message("ok").build());
        }

        private IntentExecuteResponse execute(IntentExecuteRequest request) throws Exception {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, "F001", request, 7L,
                    "factory_super_admin");
            ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
            verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
            return (IntentExecuteResponse) result.getValue();
        }
    }
}
