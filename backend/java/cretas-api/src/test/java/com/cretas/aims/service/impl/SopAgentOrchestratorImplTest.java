package com.cretas.aims.service.impl;

import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defense-in-depth test for {@link SopAgentOrchestratorImpl#evaluateCondition}
 * — B-A7 P0 sister fix (Canvas Phase 2-5 QA 2026-05-19).
 *
 * <p><b>Vulnerability before fix</b>: {@code evaluateCondition} used
 * {@link org.springframework.expression.spel.support.StandardEvaluationContext}
 * — full reflection / Type / static methods / constructors — so any rule with
 * malicious {@code conditionExpression} persisted in DB before write-time validation
 * existed (or written via SQL/direct DB access) could trigger RCE on the next
 * SOP-upload event.
 *
 * <p><b>Fix</b>: {@link SopAgentOrchestratorImpl#evaluateCondition} now delegates to
 * {@link SandboxedSpelEvaluator#evaluateBoolean} which uses
 * {@code SimpleEvaluationContext.forReadOnlyDataBinding()}. The sandbox rejects
 * forbidden constructs as {@link SandboxedSpelEvaluator.SpelEvaluationFailure},
 * caught + logged + treated as "rule doesn't match" — does NOT break the event flow.
 *
 * <p>Tests use reflection to invoke private {@code evaluateCondition} since this
 * is a defense-in-depth verification (the public {@code executeToolChain} path
 * needs a mocked repository + tools — too much setup for this focused
 * security test).
 *
 * @since 2026-05-19 (Canvas Phase 2-5 QA hotfix sister)
 */
@DisplayName("SopAgentOrchestratorImpl evaluateCondition 沙箱化 (B-A7 defense-in-depth)")
class SopAgentOrchestratorImplTest {

    private SopAgentOrchestratorImpl orchestrator;
    private Method evaluateConditionMethod;

    @BeforeEach
    void setUp() throws Exception {
        orchestrator = new SopAgentOrchestratorImpl();

        // Inject real SandboxedSpelEvaluator (Spring would normally @Autowired this)
        Field spelEvalField = SopAgentOrchestratorImpl.class.getDeclaredField("spelEvaluator");
        spelEvalField.setAccessible(true);
        spelEvalField.set(orchestrator, new SandboxedSpelEvaluator());

        // Inject ObjectMapper for completeness (evaluateCondition doesn't use it, but
        // other code paths in the orchestrator do).
        Field omField = SopAgentOrchestratorImpl.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(orchestrator, new ObjectMapper());

        // Get reference to private evaluateCondition method
        evaluateConditionMethod = SopAgentOrchestratorImpl.class.getDeclaredMethod(
                "evaluateCondition", String.class, Map.class);
        evaluateConditionMethod.setAccessible(true);
    }

    private boolean evaluateCondition(String spel, Map<String, Object> context) throws Exception {
        return (boolean) evaluateConditionMethod.invoke(orchestrator, spel, context);
    }

    // ==================== Sandbox blocks RCE at runtime (defense-in-depth) ====================

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 T(Runtime).getRuntime().exec(...) (pre-fix DB data)")
    void testEvaluateConditionRejectsRuntimeExec() throws Exception {
        // Simulate pre-fix-persisted malicious rule. Sandbox rejects, returns false.
        // The exec(...) call must NEVER actually execute.
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "PRODUCTION");
        boolean result = evaluateCondition(
                "T(java.lang.Runtime).getRuntime().exec(\"calc\")", ctx);
        assertFalse(result, "Sandbox MUST reject T(Runtime).exec → returns false (rule doesn't match)");
    }

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 new ProcessBuilder() 构造器")
    void testEvaluateConditionRejectsConstructor() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        boolean result = evaluateCondition(
                "new java.lang.ProcessBuilder('rm').start() != null", ctx);
        assertFalse(result);
    }

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 #ctx.getClass().forName(...) 反射逃逸")
    void testEvaluateConditionRejectsReflection() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("anything", "value");
        boolean result = evaluateCondition(
                "#anything.getClass().forName('java.lang.Runtime') != null", ctx);
        assertFalse(result);
    }

    @Test
    @DisplayName("B-A7 defense-in-depth: 运行时拒绝 #{...} 包装的 RCE")
    void testEvaluateConditionRejectsWrappedRce() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        // Wrapping #{...} is stripped before parse — must still hit sandbox.
        boolean result = evaluateCondition(
                "#{T(java.lang.Runtime).getRuntime().exec(\"id\")}", ctx);
        assertFalse(result);
    }

    // ==================== Valid expressions still evaluate correctly ====================

    @Test
    @DisplayName("null 表达式 → true (无条件 = 始终满足)")
    void testNullExpressionReturnsTrue() throws Exception {
        boolean result = evaluateCondition(null, new HashMap<>());
        assertTrue(result);
    }

    @Test
    @DisplayName("blank 表达式 → true")
    void testBlankExpressionReturnsTrue() throws Exception {
        boolean result = evaluateCondition("   ", new HashMap<>());
        assertTrue(result);
    }

    @Test
    @DisplayName("合法表达式正常求值: #sopType == 'PRODUCTION' (match)")
    void testValidExpressionEvaluatesTrue() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "PRODUCTION");
        boolean result = evaluateCondition("#sopType == 'PRODUCTION'", ctx);
        assertTrue(result, "合法 SpEL 表达式应正常求值");
    }

    @Test
    @DisplayName("合法表达式正常求值: #sopType == 'PRODUCTION' (no-match)")
    void testValidExpressionEvaluatesFalse() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "QUALITY");
        boolean result = evaluateCondition("#sopType == 'PRODUCTION'", ctx);
        assertFalse(result, "条件不满足时返 false");
    }

    @Test
    @DisplayName("合法表达式: #{...} 包装的属性比较 (mirror old runtime contract)")
    void testValidWrappedExpression() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sopType", "PRODUCTION");
        boolean result = evaluateCondition("#{#sopType == 'PRODUCTION'}", ctx);
        assertTrue(result);
    }

    @Test
    @DisplayName("不存在字段 → 沙箱返 SpelEvaluationFailure → false (不破坏 event 循环)")
    void testUnknownFieldReturnsFalse() throws Exception {
        Map<String, Object> ctx = new HashMap<>();
        boolean result = evaluateCondition("#nonExistent.field == 'x'", ctx);
        assertFalse(result, "未知字段不应抛异常, 仅返 false 让 rule 不 match");
    }

    // ==================== S6 (2026-10-29): SOP chain steps go through the permission chain ====================

    @org.junit.jupiter.api.Test
    @DisplayName("SOP 工具链: 每一步按上传人当前权限过同一条权限链; 无执行人 / 别厂账号 / 被拒 → 不执行")
    void eachSopChainStepIsCheckedAsTheUploaderAndDeniedStepsNeverRun() throws Exception {
        com.cretas.aims.repository.smartbi.AiAgentRuleRepository rules =
                org.mockito.Mockito.mock(com.cretas.aims.repository.smartbi.AiAgentRuleRepository.class);
        com.cretas.aims.ai.tool.ToolRegistry registry = org.mockito.Mockito.mock(com.cretas.aims.ai.tool.ToolRegistry.class);
        com.cretas.aims.ai.tool.ToolExecutor tool = org.mockito.Mockito.mock(com.cretas.aims.ai.tool.ToolExecutor.class);
        org.mockito.Mockito.when(tool.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("{\"success\":true}");
        org.mockito.Mockito.when(registry.getExecutor("balance_sheet_query")).thenReturn(java.util.Optional.of(tool));
        com.cretas.aims.entity.smartbi.AiAgentRule rule = new com.cretas.aims.entity.smartbi.AiAgentRule();
        rule.setToolChainConfig("{\"tools\":[{\"toolName\":\"balance_sheet_query\",\"paramsMapping\":{}}]}");
        org.mockito.Mockito.when(rules.findById("r1")).thenReturn(java.util.Optional.of(rule));
        com.cretas.aims.repository.UserRepository users = org.mockito.Mockito.mock(com.cretas.aims.repository.UserRepository.class);
        com.cretas.aims.entity.User uploader = new com.cretas.aims.entity.User();
        uploader.setId(7L);
        uploader.setFactoryId("F006");
        uploader.setRoleCode("production_manager");
        uploader.setIsActive(true);
        org.mockito.Mockito.when(users.findById(7L)).thenReturn(java.util.Optional.of(uploader));
        com.cretas.aims.service.execution.ToolPermissionChain chain =
                org.mockito.Mockito.mock(com.cretas.aims.service.execution.ToolPermissionChain.class);
        org.mockito.Mockito.when(chain.denyReason(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(java.util.Optional.of("无权查看"));
        for (String[] field : new String[][]{{"ruleRepository"}, {"toolRegistry"}, {"toolPermissionChain"}, {"userRepository"}}) {
            Field f = SopAgentOrchestratorImpl.class.getDeclaredField(field[0]);
            f.setAccessible(true);
            f.set(orchestrator, switch (field[0]) {
                case "ruleRepository" -> rules;
                case "toolRegistry" -> registry;
                case "toolPermissionChain" -> chain;
                default -> users;
            });
        }

        Map<String, Object> denied = orchestrator.executeRuleToolChain("r1",
                new HashMap<>(Map.of("factoryId", "F006", "uploadedBy", 7L)));
        Map<String, Object> noUser = orchestrator.executeRuleToolChain("r1", new HashMap<>(Map.of("factoryId", "F006")));
        Map<String, Object> otherFactory = orchestrator.executeRuleToolChain("r1",
                new HashMap<>(Map.of("factoryId", "F999", "uploadedBy", 7L)));
        assertFalse((Boolean) denied.get("success"));
        assertFalse((Boolean) noUser.get("success"));
        assertFalse((Boolean) otherFactory.get("success"));
        org.mockito.Mockito.verify(tool, org.mockito.Mockito.never())
                .execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());

        // Positive control: the same step runs once the chain admits the uploader.
        org.mockito.Mockito.when(chain.denyReason(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(java.util.Optional.empty());
        orchestrator.executeRuleToolChain("r1", new HashMap<>(Map.of("factoryId", "F006", "uploadedBy", 7L)));
        org.mockito.Mockito.verify(tool).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }
}
