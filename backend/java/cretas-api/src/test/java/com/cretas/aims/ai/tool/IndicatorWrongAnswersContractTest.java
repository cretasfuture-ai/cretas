package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.impl.indicator.IndicatorQueryTool;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.service.execution.PhraseShortcutEligibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.ai.dto.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-18 LSMDEMO 问数据答非所问 4 条 (毛利最低 / 显式月份 / 出成率异常 / 损耗率), 用原话走识别短语表、
 * 短语快捷放行条件、时间归一、SkillExecutor 缺参追问、指标工具入口。
 */
class IndicatorWrongAnswersContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- 1. 毛利最低的产品: 有短语、且整句放行快捷 ----
    @ParameterizedTest
    @ValueSource(strings = {"这个月毛利最低的产品是哪些？", "毛利最低的产品是哪些", "哪些产品毛利最低", "这个月毛利率最低的产品有哪些", "毛利最高的产品是哪些"})
    void lowestMarginQuestionsGoToSkuGrossMargin(String q) {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(q, "FACTORY");
        assertTrue(hit.isPresent(), q);
        assertEquals("SKU_GROSS_MARGIN", hit.get().getValue(), q);
        assertTrue(PhraseShortcutEligibility.isUnconditionedSimple(q, hit.get().getKey(), hit.get().getValue()), q);
    }

    @Test
    void marginRankingOfCustomersIsNotStolenBySkuMargin() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        assertNotEquals("SKU_GROSS_MARGIN", kb.matchPhrase("这个月毛利最低的客户是哪些", "FACTORY").orElse(null));
    }

    // ---- 2. 显式月份 ----
    @ParameterizedTest
    @ValueSource(strings = {"10 月各客户销售额排行", "10月各客户销售额排行", "十月各客户销售额排行"})
    void bareMonthIsParsedToThatMonth(String q) {
        var res = new TimeNormalizationRules().extractAndReplace(q, LocalDateTime.of(2026, 11, 18, 20, 0));
        assertEquals(1, res.getExtractedRanges().size(), q);
        var range = res.getExtractedRanges().get(0);
        assertEquals(java.time.LocalDate.of(2026, 10, 1), range.getStart().toLocalDate(), q);
        assertEquals(java.time.LocalDate.of(2026, 10, 31), range.getEnd().toLocalDate(), q);
        assertTrue(TimeRangeFilter.mentionsTime(q));
    }

    @Test
    void bareMonthNegativeControls() {
        TimeNormalizationRules rules = new TimeNormalizationRules();
        LocalDateTime now = LocalDateTime.of(2026, 11, 18, 20, 0);
        // 未来的月份取去年
        assertEquals(java.time.LocalDate.of(2025, 12, 1),
                rules.extractAndReplace("12月销售额", now).getExtractedRanges().get(0).getStart().toLocalDate());
        // 带年份的仍由原规则管, 只得到一段
        assertEquals(1, rules.extractAndReplace("今年3月销售额", now).getExtractedRanges().size());
        // 具体日期 / 区间不当单月
        assertTrue(rules.extractAndReplace("10月5号的订单", now).getExtractedRanges().stream()
                .noneMatch(x -> x.getEnd().toLocalDate().equals(java.time.LocalDate.of(2026, 10, 31))));
        // 没说时间
        assertTrue(rules.extractAndReplace("各客户销售额排行", now).getExtractedRanges().isEmpty());
    }

    // ---- 4. 损耗率: 不外露指标码 ----
    @Test
    void unknownIndicatorDoesNotLeakTheCode() throws Exception {
        IndicatorRepository repo = mock(IndicatorRepository.class);
        when(repo.findByCodeAndFactoryIdAndDeletedAtIsNull(anyString(), anyString())).thenReturn(Optional.empty());
        Indicator known = new Indicator();
        known.setName("客单价");
        when(repo.findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc("F1")).thenReturn(List.of(known));
        IndicatorQueryTool tool = new IndicatorQueryTool();
        ReflectionTestUtils.setField(tool, "indicatorRepository", repo);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t", "indicator_query", "{\"indicator_code\":\"RAW_WASTAGE_RATE\"}"),
                Map.of("factoryId", "F1", "userId", 1L)));
        String message = r.get("data").get("message").asText();
        assertFalse(message.contains("RAW_WASTAGE_RATE"), message);
        assertTrue(message.contains("暂不支持") && message.contains("客单价"), message);
        // schema 不再把已删除的镜像指标码当示例教给模型
        assertFalse(tool.getParametersSchema().toString().contains("RAW_WASTAGE_RATE"));
    }
}
