package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 给其它包的测试用的「真编排器」: 意图识别结果由调用方给 (显式意图码), 之后的
 * <b>权限门 → READ 模式写拦截</b> 先后顺序、两种拦截的响应形状全是生产代码 ({@link IntentExecutionOrchestrator}
 * + 真 {@link IntentPermissionGate} + 真 {@link WriteGuardService})。只有「这个人有没有这个权限码」由
 * {@code granted} 决定 (那是权限服务自己的测试)。
 *
 * <p>为什么要有它: 数字班组靠编排器的响应形状决定交不交办; 只把形状桩成「READ_MODE_WRITE_BLOCKED」
 * 会漏掉「没写权限的人先被权限门拦下、拿到 NO_PERMISSION」这一态 (#1771 审查 中-1, 形态 B: 桩的形状要能在生产出现)。
 */
public final class RealPermissionOrderOrchestratorFixture {

    private final IntentExecutionOrchestrator orchestrator;
    private final AIIntentService aiIntentService;

    public RealPermissionOrderOrchestratorFixture(BiPredicate<Long, String[]> granted) {
        aiIntentService = mock(AIIntentService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", mock(PreviewTokenService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        ToolRbacGuard rbacGuard = mock(ToolRbacGuard.class);
        when(rbacGuard.hasAnyPermission(any(), any(String[].class))).thenAnswer(inv -> {
            Map<?, ?> ctx = inv.getArgument(0);
            Object[] all = inv.getArguments();
            String[] codes = new String[all.length - 1];
            for (int i = 1; i < all.length; i++) {
                Object a = all[i];
                if (a instanceof String[] arr) {
                    codes = arr;
                    break;
                }
                codes[i - 1] = (String) a;
            }
            return granted.test((Long) ctx.get("userId"), codes);
        });
        IntentPermissionGate gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", rbacGuard);
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
        ReflectionTestUtils.setField(orchestrator, "demoFactoryIdsCsv", "DEMO_FACTORY2,DEMO_FACTORY");
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
    }

    /**
     * 编排器在一个已登记业态的制造工厂下跑 (业态门不是这里要守的东西, 调用方的工厂号不进编排器);
     * 权限码判定只看 userId, 与工厂号无关。
     */
    static final String ORCHESTRATOR_FACTORY = "F006";

    /** 登记一个意图配置。 */
    public void register(AIIntentConfig intent) {
        when(aiIntentService.getIntentByCode(ORCHESTRATOR_FACTORY, intent.getIntentCode())).thenReturn(Optional.of(intent));
    }

    /** 识别结果 = intentCode, 其余 (权限门 / READ 写拦截) 走真编排器。 */
    public IntentExecuteResponse execute(IntentExecuteRequest request, String intentCode, Long userId, String userRole) {
        IntentExecuteRequest recognized = IntentExecuteRequest.builder()
                .userInput(request.getUserInput()).sessionId(request.getSessionId()).mode(request.getMode())
                .intentCode(intentCode).build();
        return orchestrator.executeWithExplicitIntent(ORCHESTRATOR_FACTORY, recognized, userId, userRole);
    }
}
