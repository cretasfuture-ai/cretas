package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.source.SourceIndividualQueryTool;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.slot.RequiredSlot;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.DnaVerificationStatus;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 溯源 / 源头个体两个意图的槽位抽参 —— 用真实的 {@link IntentSlotConfiguration}。
 *
 * <p>实测长相 (prod CATTLE_3701_001, 2026-09-15 20:5x): 四个问句意图都认出来了
 * (LINEAGE_QUERY / SOURCE_INDIVIDUAL_QUERY), 但 Tool 全部「工具提前返回 NEED_MORE_INFO:
 * missingParams=[batch_number]」/「[individual_code]」。成因: 两个 Tool 的 schema
 * {@code required} 是空表（二选一 / 三选一）, 而 SlotFilling 只对 required 抽参,
 * 用户原文里的 SL-DEMO-BATCH-V1 / SL-BULL-001 从来没有被送到 Tool 手上。
 */
class SlotFillingLineageAndSourceIndividualTest {

    private IntentSlotConfiguration slotConfiguration;
    private SlotFillingServiceImpl service;
    private PythonLLMClient llm;

    private static AIIntentConfig intent(String code, String tool) {
        return AIIntentConfig.builder().intentCode(code).intentName(code).toolName(tool).build();
    }

    private static IntentExecuteRequest request(String userInput) {
        return IntentExecuteRequest.builder()
                .userInput(userInput)
                .context(new HashMap<>())
                .build();
    }

    @BeforeEach
    void setUp() {
        slotConfiguration = new IntentSlotConfiguration();
        slotConfiguration.init();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ConversationService conversationService = mock(ConversationService.class);
        ParameterExtractionLearningService learningService = mock(ParameterExtractionLearningService.class);
        when(conversationService.startParameterCollection(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        service = new SlotFillingServiceImpl(slotConfiguration, toolRegistry, conversationService, learningService);
        llm = mock(PythonLLMClient.class);
        when(llm.chatLowTemp(anyString(), anyString())).thenReturn("{}");
        service.setPythonLLMClient(llm);
    }

    // ── LINEAGE_QUERY ────────────────────────────────────────────────────

    @Test
    @DisplayName("批次溯源: 原文里的批号被抽成 batch_number 并写进请求上下文, 不追问")
    void lineageQueryExtractsBatchNumberFromUtterance() {
        IntentExecuteRequest req = request("批次 SL-DEMO-BATCH-V1 来自哪几头牛");

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(
                "CATTLE_3701_001", 1L, intent("LINEAGE_QUERY", "lineage_query"), req, null);

        assertThat(resp).as("批号已在原文里, 不该进追问").isNull();
        assertThat(req.getContext()).containsEntry("batch_number", "SL-DEMO-BATCH-V1");
        verify(llm, never()).chatLowTemp(anyString(), anyString());
    }

    @Test
    @DisplayName("对照: 没说批号 → batch_number 仍是必填, 走追问")
    void lineageQueryStillAsksWhenNoBatchNumber() {
        List<RequiredSlot> slots = service.getRequiredSlots(intent("LINEAGE_QUERY", "lineage_query"));
        Map<String, Object> extracted = service.extractParameters("这批来自哪几头牛", slots, new HashMap<>(), null);

        List<RequiredSlot> missing = service.findMissingSlots(slots, extracted);
        assertThat(missing).extracting(RequiredSlot::getName).containsExactly("batch_number");
        // 必填缺失时 LLM 兜底必须被叫到（它抽不到才追问）
        verify(llm, times(1)).chatLowTemp(anyString(), anyString());
    }

    // ── SOURCE_INDIVIDUAL_QUERY ──────────────────────────────────────────

    @Test
    @DisplayName("源头个体: 编码 + 「后代」→ individual_code / ask=offspring, 不追问, 不叫 LLM")
    void sourceIndividualExtractsCodeAndAsk() {
        IntentExecuteRequest req = request("SL-BULL-001 这头公牛有哪些后代");

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(
                "CATTLE_3701_001", 1L, intent("SOURCE_INDIVIDUAL_QUERY", "source_individual_query"), req, null);

        assertThat(resp).isNull();
        assertThat(req.getContext()).containsEntry("individual_code", "SL-BULL-001");
        assertThat(SourceIndividualQueryTool.normalizeAsk((String) req.getContext().get("ask")))
                .isEqualTo("offspring");
        verify(llm, never()).chatLowTemp(anyString(), anyString());
    }

    @Test
    @DisplayName("源头个体: 「DNA 比对不匹配」→ dna_status 可解析成 MISMATCH")
    void sourceIndividualExtractsDnaStatus() {
        IntentExecuteRequest req = request("哪一头牛的 DNA 比对不匹配");

        service.checkAndStartSlotFilling(
                "CATTLE_3701_001", 1L, intent("SOURCE_INDIVIDUAL_QUERY", "source_individual_query"), req, null);

        assertThat(req.getContext()).containsKey("dna_status");
        assertThat(SourceIndividualQueryTool.parseDnaStatus((String) req.getContext().get("dna_status")))
                .isEqualTo(DnaVerificationStatus.MISMATCH);
        assertThat(req.getContext()).doesNotContainKey("individual_code");
    }

    @Test
    @DisplayName("源头个体: 「去了哪」→ ask=downstream; 耳标数字 → tag_number")
    void sourceIndividualExtractsDownstreamAndTag() {
        IntentExecuteRequest req = request("SL-CATTLE-005 这头牛去了哪");
        service.checkAndStartSlotFilling(
                "CATTLE_3701_001", 1L, intent("SOURCE_INDIVIDUAL_QUERY", "source_individual_query"), req, null);
        assertThat(req.getContext()).containsEntry("individual_code", "SL-CATTLE-005");
        assertThat(SourceIndividualQueryTool.normalizeAsk((String) req.getContext().get("ask")))
                .isEqualTo("downstream");

        IntentExecuteRequest tagReq = request("耳标 110370281000001 这头牛的档案");
        service.checkAndStartSlotFilling(
                "CATTLE_3701_001", 1L, intent("SOURCE_INDIVIDUAL_QUERY", "source_individual_query"), tagReq, null);
        assertThat(tagReq.getContext()).containsEntry("tag_number", "110370281000001");
        assertThat(tagReq.getContext()).doesNotContainKey("individual_code");
    }

    @Test
    @DisplayName("源头个体: 自由措辞一个槽位都没抽到 → 才叫 LLM 兜底, 且仍不追问")
    void sourceIndividualFallsBackToLlmOnlyWhenNothingExtracted() {
        when(llm.chatLowTemp(anyString(), anyString()))
                .thenReturn("{\"individual_code\": null, \"tag_number\": null, \"dna_status\": \"MISMATCH\", \"ask\": null}");
        IntentExecuteRequest req = request("那几头 DNA 对不上的牛是谁");

        IntentExecuteResponse resp = service.checkAndStartSlotFilling(
                "CATTLE_3701_001", 1L, intent("SOURCE_INDIVIDUAL_QUERY", "source_individual_query"), req, null);

        assertThat(resp).as("全可选槽位, 抽不到也不追问, 交给 Tool 判三选一").isNull();
        verify(llm, times(1)).chatLowTemp(anyString(), anyString());
        assertThat(req.getContext()).containsEntry("dna_status", "MISMATCH");
        assertThat(req.getContext()).doesNotContainKey("individual_code");
    }

    @Test
    @DisplayName("normalizeAsk: 枚举原样, 中文映射, 认不出的原样返回 (不静默改成 profile)")
    void normalizeAskMapsChineseButDoesNotInventProfile() {
        assertThat(SourceIndividualQueryTool.normalizeAsk("Offspring")).isEqualTo("offspring");
        assertThat(SourceIndividualQueryTool.normalizeAsk("同父")).isEqualTo("siblings");
        assertThat(SourceIndividualQueryTool.normalizeAsk("父本母本")).isEqualTo("pedigree");
        assertThat(SourceIndividualQueryTool.normalizeAsk("胡说")).isEqualTo("胡说");
        assertThat(SourceIndividualQueryTool.normalizeAsk("  ")).isNull();
    }
}
