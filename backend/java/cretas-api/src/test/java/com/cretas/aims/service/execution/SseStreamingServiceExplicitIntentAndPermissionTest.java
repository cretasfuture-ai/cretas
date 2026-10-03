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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * 流式路径 (App 实际走的那条) 的两件事 (2026-10-03):
 * <ol>
 *   <li>澄清按钮点选后带「意图码 + 原话」回来: 跳过识别、原话原样交给执行, 并按标记记录点选;</li>
 *   <li>意图声明的权限码在流式路径上也被检查 (此前只查 requiredRoles)。</li>
 * </ol>
 */
class SseStreamingServiceExplicitIntentAndPermissionTest {

    @Test
    void explicitIntentCodeSkipsRecognitionAndKeepsTheOriginalSentence() throws Exception {
        Harness h = new Harness();
        AIIntentConfig intent = h.readIntent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", null);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("就是六十箱").intentCode("WORK_PROCESS_TASK_COMPLETE").build();

        IntentExecuteResponse result = h.execute(request);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(h.aiIntentService, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), anyLong(), anyString(), any(), any(), any(), any());
        ArgumentCaptor<IntentExecuteRequest> sent = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        verify(h.toolDispatchService).executeWithTool(any(), eq("F001"), sent.capture(), eq(intent),
                eq(7L), eq("factory_super_admin"), any());
        assertThat(sent.getValue().getUserInput()).isEqualTo("就是六十箱");
    }

    @Test
    void unknownExplicitCodeDoesNotTakeTheExplicitPath() throws Exception {
        // 已停用 / 不存在的意图码 (例如 CUSTOMER_DELETE 被停用后): 不走显式执行, 交回正常流程。
        Harness h = new Harness();
        when(h.aiIntentService.getIntentByCode("F001", "CUSTOMER_DELETE")).thenReturn(Optional.empty());
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("把这个客户删了").intentCode("CUSTOMER_DELETE")
                .context(Map.of("clarificationSelection", true)).build();

        h.executeIgnoringResult(request);

        verify(h.service, never()).sendSseEvent(eq(h.emitter), eq("intent_recognized"),
                org.mockito.ArgumentMatchers.argThat(m -> m instanceof Map<?, ?> map
                        && "EXPLICIT".equals(map.get("matchMethod"))));
        verify(h.pythonClient, after(300).never()).recordSelection(any(), any(), any(), any(), any(), any());
    }

    @Test
    void knownExplicitCodeDoesTakeTheExplicitPath() throws Exception {
        // 对照: 同样的断言在码存在时为真 —— 证明上一条的「没走显式」来自查不到意图。
        Harness h = new Harness();
        h.readIntent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", null);
        h.execute(IntentExecuteRequest.builder()
                .userInput("就是六十箱").intentCode("WORK_PROCESS_TASK_COMPLETE").build());

        verify(h.service).sendSseEvent(eq(h.emitter), eq("intent_recognized"),
                org.mockito.ArgumentMatchers.argThat(m -> m instanceof Map<?, ?> map
                        && "EXPLICIT".equals(map.get("matchMethod"))));
    }

    @Test
    void permissionCodeIsEnforcedOnTheStreamingPath() throws Exception {
        Harness h = new Harness();
        AIIntentConfig intent = h.readIntent("RULE_CONFIG", "rule_config", "system:read_write");
        when(h.permissionGate.check(intent, 7L, "factory_super_admin"))
                .thenReturn(IntentPermissionGate.PermissionCheck.denied("system:read_write"));
        // 旧的角色判定会放行 —— 证明拒绝来自权限码门, 不是角色
        when(h.aiIntentService.hasPermission("RULE_CONFIG", "factory_super_admin")).thenReturn(true);

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder()
                .userInput("把规则改一下").intentCode("RULE_CONFIG").build());

        assertThat(result.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(result.getMessage()).contains("system:read_write");
        verify(h.toolDispatchService, never()).executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    void permissionCodeAllowedLetsExecutionContinue() throws Exception {
        Harness h = new Harness();
        AIIntentConfig intent = h.readIntent("RULE_CONFIG", "rule_config", "system:read_write");
        when(h.permissionGate.check(intent, 7L, "factory_super_admin"))
                .thenReturn(IntentPermissionGate.PermissionCheck.allowed());

        IntentExecuteResponse result = h.execute(IntentExecuteRequest.builder()
                .userInput("把规则改一下").intentCode("RULE_CONFIG").build());

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void clarificationPickIsRecordedWithTheOriginalSentence() throws Exception {
        Harness h = new Harness();
        h.readIntent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", null);
        h.execute(IntentExecuteRequest.builder()
                .userInput("就是六十箱").intentCode("WORK_PROCESS_TASK_COMPLETE")
                .context(Map.of("clarificationSelection", true,
                        "clarificationCandidates", List.of("WORK_PROCESS_TASK_COMPLETE", "ABACA_WEIGHT_LOG")))
                .build());

        verify(h.pythonClient, timeout(2000)).recordSelection(
                "F001", 7L, "factory_super_admin", "就是六十箱", "WORK_PROCESS_TASK_COMPLETE",
                List.of("WORK_PROCESS_TASK_COMPLETE", "ABACA_WEIGHT_LOG"));
    }

    @Test
    void explicitCodeWithoutClarificationFlagIsNotRecorded() throws Exception {
        Harness h = new Harness();
        h.readIntent("WORK_PROCESS_TASK_COMPLETE", "work_process_task_complete", null);
        h.execute(IntentExecuteRequest.builder()
                .userInput("就是六十箱").intentCode("WORK_PROCESS_TASK_COMPLETE").build());

        verify(h.pythonClient, after(300).never()).recordSelection(any(), any(), any(), any(), any(), any());
    }

    private static final class Harness {
        private final AIIntentService aiIntentService = mock(AIIntentService.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final ToolDispatchService toolDispatchService = mock(ToolDispatchService.class);
        private final WriteGuardService writeGuardService = mock(WriteGuardService.class);
        private final BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        private final IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        private final PythonAiMatcherClient pythonClient = mock(PythonAiMatcherClient.class);
        private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
        private final SseStreamingService service;
        private final SseEmitter emitter = mock(SseEmitter.class);

        private Harness() throws Exception {
            Factory factory = new Factory();
            factory.setId("F001");
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));
            FactoryCapabilityPackRoutingPolicy policy = new FactoryCapabilityPackRoutingPolicy(
                    factoryRepository,
                    new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()),
                    false);
            service = spy(new SseStreamingService(
                    aiIntentService, mock(SemanticCacheService.class), mock(IntentKnowledgeBase.class),
                    mock(AnalysisRouterService.class), new ObjectMapper(), toolRegistry, toolDispatchService,
                    mock(DynamicToolSelectionService.class), mock(DashScopeClient.class), mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", policy);
            ReflectionTestUtils.setField(service, "writeGuardService", writeGuardService);
            ReflectionTestUtils.setField(service, "businessTypeGate", businessTypeGate);
            ReflectionTestUtils.setField(service, "intentPermissionGate", permissionGate);
            ReflectionTestUtils.setField(service, "pythonAiMatcherClient", pythonClient);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
        }

        private AIIntentConfig readIntent(String code, String tool, String permission) {
            AIIntentConfig intent = AIIntentConfig.builder()
                    .intentCode(code).intentName(code).intentCategory("QUERY").businessType("FACTORY")
                    .sensitivityLevel("LOW").toolName(tool).requiredPermission(permission).build();
            when(aiIntentService.getIntentByCode("F001", code)).thenReturn(Optional.of(intent));
            when(aiIntentService.hasPermission(code, "factory_super_admin")).thenReturn(true);
            ToolExecutor executor = mock(ToolExecutor.class);
            when(toolRegistry.getExecutor(tool)).thenReturn(Optional.of(executor));
            when(toolDispatchService.executeWithTool(eq(executor), eq("F001"), any(), eq(intent), eq(7L),
                    eq("factory_super_admin"), any()))
                    .thenReturn(IntentExecuteResponse.builder().intentRecognized(true).intentCode(code)
                            .status("COMPLETED").message("ok").build());
            return intent;
        }

        private IntentExecuteResponse execute(IntentExecuteRequest request) throws Exception {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, "F001", request, 7L,
                    "factory_super_admin");
            ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
            verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
            return (IntentExecuteResponse) result.getValue();
        }

        private void executeIgnoringResult(IntentExecuteRequest request) {
            try {
                ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, "F001", request, 7L,
                        "factory_super_admin");
            } catch (Exception ignored) {
                // 识别被 mock 成 null, 下游会失败 —— 这里只关心「是否调用了识别」
            }
        }
    }
}
