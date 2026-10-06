package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.MultiIntentResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 2026-10-06 prod 实测: 澄清按钮的副标题出现「置信度: 39%」和开发者备注
 * (「Sprint 10 Loop 5 灵魂 Tool — 创建 ProductionBatch」「Sprint 10 Loop 1 — confirm 模式, 接 salesOrderId」)。
 *
 * <p>走产品真实入口 {@link ClarificationCandidateService#buildCandidateActions} (真服务, 只桩配置查询 / 权限),
 * 与多意图确认响应; 每条「不显示」都配一条「正常描述照常显示」的对照。
 */
@DisplayName("澄清按钮副标题: 不显示置信度, 不漏开发者备注")
class ClarificationButtonSubtitleTest {

    private static final String FACTORY = "F006";
    /** 用户在 prod 按钮上截到的原文 (代表三种写法)。 */
    private static final List<String> DEVELOPER_NOTES = List.of(
            "Sprint 8 P4c 质量主管放行前 HACCP 状态简述查询",
            "Sprint 10 Loop 5 灵魂 Tool — 创建 ProductionBatch (status=PLANNED) 并锁定 SO 行项目",
            "Sprint 10 Loop 1 — confirm 模式, 接 salesOrderId");

    private IntentConfigManagementService configService;
    private ClarificationCandidateService service;

    @BeforeEach
    void setUp() {
        configService = mock(IntentConfigManagementService.class);
        IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        IntentAccessModeFilter accessModeFilter = mock(IntentAccessModeFilter.class);
        service = new ClarificationCandidateService(
                configService, permissionGate, accessModeFilter, new WriteGuardService());
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        when(permissionGate.check(any(), eq(7L), eq("manager")))
                .thenReturn(IntentPermissionGate.PermissionCheck.allowed());
        when(configService.hasPermission(eq(FACTORY), anyString(), eq("manager"))).thenReturn(true);
        when(accessModeFilter.isWriteIntent(any(AIIntentConfig.class))).thenReturn(false);
    }

    @Test
    @DisplayName("没有描述的意图: 按钮没有副标题, 任何字段都不含「置信度」(识别分数 39% 不给一线看)")
    void noDescriptionMeansNoSubtitleAndNoConfidenceText() {
        IntentExecuteResponse.SuggestedAction action = buttonFor("查询温度记录", null, 0.39);

        assertThat(action.getDescription()).isNull();
        assertThat(String.valueOf(action.getActionName())).doesNotContain("置信度").doesNotContain("%");
        // 对照: 同一条路径上, 有描述的意图照常把描述当副标题 —— 证明上面的 null 来自「没描述」而不是「整条路径不出副标题」
        assertThat(buttonFor("查询温度记录", "查询冷链设备最近 24 小时的温度曲线", 0.39).getDescription())
                .isEqualTo("查询冷链设备最近 24 小时的温度曲线");
    }

    @Test
    @DisplayName("描述是空白串: 同样没有副标题")
    void blankDescriptionMeansNoSubtitle() {
        assertThat(buttonFor("开始工序", "   ", 0.39).getDescription()).isNull();
    }

    @Test
    @DisplayName("描述是开发者备注 (prod 截到的三条原文): 不当副标题显示")
    void developerNotesAreNotShownAsSubtitle() {
        Set<String> seen = new HashSet<>();
        for (String note : DEVELOPER_NOTES) {
            seen.add(note);
            IntentExecuteResponse.SuggestedAction action = buttonFor("某功能", note, 0.5);
            assertThat(action.getDescription()).as(note).isNull();
        }
        assertThat(seen).as("参数化的那一维真的在变").hasSize(DEVELOPER_NOTES.size());
    }

    @Test
    @DisplayName("对照: 改写后的一线描述 (V20261101_58 的新文本样例) 照常显示, 不被开发者字样判据误杀")
    void rewrittenFrontlineDescriptionsAreShown() {
        for (String plain : List.of(
                "质量主管放行前快速查看批次的 HACCP 情况：整体是否通过、有几个监控点偏离，并给出能否放行的提示；只查询。",
                "创建生产批次（初始状态为计划中）并锁定对应销售订单行；预览会显示数量上限、品名和订单、生产线选择。",
                "确认发货并创建发货单：按销售订单和各订单行的实发数量创建，数量不能超过待发量。")) {
            assertThat(buttonFor("某功能", plain, 0.5).getDescription()).as(plain).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("多意图确认按钮: 不显示「置信度: xx%」, 也不贴 LLM 的识别理由; 功能名照常在")
    void multiIntentConfirmationButtonsHaveNoConfidenceText() {
        MultiIntentExecutionService multi = mock(MultiIntentExecutionService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Map<String, Object> params = new LinkedHashMap<>();
        MultiIntentResult result = MultiIntentResult.builder()
                .isMultiIntent(true)
                .executionStrategy(MultiIntentResult.ExecutionStrategy.SEQUENTIAL)
                .overallConfidence(0.85)
                .intents(List.of(
                        MultiIntentResult.SingleIntentMatch.builder()
                                .intentCode("MATERIAL_BATCH_QUERY").intentName("查询原材料批次")
                                .confidence(0.85).reasoning("用户提到库存 → MATERIAL_BATCH_QUERY")
                                .extractedParams(params).build(),
                        MultiIntentResult.SingleIntentMatch.builder()
                                .intentCode("QUALITY_STATS").intentName("查询质检统计")
                                .confidence(0.62).reasoning("匹配 QUALITY_STATS")
                                .extractedParams(params).build()))
                .build();

        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                multi, "buildMultiIntentUserConfirmationResponse", result, FACTORY);

        assertThat(response).isNotNull();
        List<IntentExecuteResponse.SuggestedAction> perIntent = response.getSuggestedActions().stream()
                .filter(a -> a.getActionCode() != null && !"EXECUTE_ALL".equals(a.getActionCode())).toList();
        // 对照 (必发生): 两个意图的按钮确实都生成了, 且功能名在
        assertThat(perIntent).extracting(IntentExecuteResponse.SuggestedAction::getActionName)
                .containsExactly("查询原材料批次", "查询质检统计");
        for (IntentExecuteResponse.SuggestedAction a : perIntent) {
            assertThat(a.getDescription()).as(a.getActionName()).isNull();
        }
    }

    @Test
    @DisplayName("按钮文本规则本身: 空 / 空白 / 开发者备注 → null; 正常描述原样返回")
    void subtitleRuleUnit() {
        assertThat(ClarificationButtonText.subtitle(null)).isNull();
        assertThat(ClarificationButtonText.subtitle("")).isNull();
        for (String note : DEVELOPER_NOTES) {
            assertThat(ClarificationButtonText.subtitle(note)).as(note).isNull();
            assertThat(ClarificationButtonText.looksLikeDeveloperText(note)).as(note).isTrue();
        }
        assertThat(ClarificationButtonText.subtitle("查看今天要收的货")).isEqualTo("查看今天要收的货");
        assertThat(ClarificationButtonText.looksLikeDeveloperText("按 GB 2760-2014 校验添加剂限量，标出超限项")).isFalse();
    }

    // ==================== helpers ====================

    private IntentExecuteResponse.SuggestedAction buttonFor(String name, String description, double confidence) {
        String code = "SOME_QUERY";
        AIIntentConfig config = AIIntentConfig.builder()
                .intentCode(code).intentName(name).description(description)
                .businessType("COMMON").sensitivityLevel("LOW").build();
        config.setIsActive(true);
        when(configService.getIntentByCode(FACTORY, code)).thenReturn(Optional.of(config));
        IntentMatchResult match = IntentMatchResult.builder().topCandidates(List.of(
                IntentMatchResult.CandidateIntent.builder()
                        .intentCode(code).intentName("untrusted").confidence(confidence).build())).build();

        List<IntentExecuteResponse.SuggestedAction> actions =
                service.buildCandidateActions(match, FACTORY, 7L, "manager", "READ");

        List<IntentExecuteResponse.SuggestedAction> selects = actions.stream()
                .filter(a -> "SELECT_INTENT".equals(a.getActionCode())).toList();
        // 对照 (必发生): 选择按钮确实生成了 —— 否则下面对 description 的任何断言都没有意义
        assertThat(selects).as("SELECT_INTENT 按钮必须生成").hasSize(1);
        assertThat(selects.get(0).getActionName()).isEqualTo(name);
        return selects.get(0);
    }
}
