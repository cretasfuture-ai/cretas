package com.cretas.aims.service.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.cretas.aims.service.reports.OperatingReportSnapshot.*;

/** BP 问题 32/33/47: 模型输出宽容解析 + 正文业务口径 + 百分比不取整 + 跟进分析 + 全 0 期间提示。 */
class OperatingReportBusinessWordingContractTest {
    private final OperatingReportCodec codec = new OperatingReportCodec(new ObjectMapper());

    private OperatingReportSnapshot snapshot(String orders, List<Insight> insights) {
        var metrics = List.of(
                new Metric("sales.orders", "订单数", new BigDecimal(orders), "单", null, null, "salesperson-performance.summary.orderCount"),
                new Metric("sales.mom", "环比", new BigDecimal("8.224"), "%", null, null, "(current-previous)/previous*100"),
                new Metric("sales.taxed", "含税销售额", new BigDecimal("1000"), "元", null, new BigDecimal("0.333333"), "x"));
        return new OperatingReportSnapshot(SCHEMA, Status.SUCCESS, "monthly", "2026-10-01", "2026-10-31", "2026-11-01T00:00:00Z",
                List.of(new SourceReceipt("sales-preset/salesperson-performance", "2026-11-01T00:00:00Z", "2026-10-01..2026-10-31"),
                        new SourceReceipt("ar-ap/aging?counterpartyType=CUSTOMER", "2026-11-01T00:00:00Z", "as of 2026-11-18; outstanding balance by due date")),
                List.of(new Section("sales", "销售额、环比与同比", true, "含税=未税+税额", metrics)), insights, null);
    }

    @Test void fencedJsonWithoutLanguageTagAndLeadingProseIsAccepted() {
        var s = snapshot("5", null);
        String json = "{\"conclusions\":[{\"text\":\"环比 {{sales.mom}} 说明增长\",\"refs\":[\"sales.mom\"]}]}";
        assertEquals(1, codec.parseModelOutput("```\n" + json + "\n```", s).size());
        assertEquals(1, codec.parseModelOutput("好的，结果如下：\n" + json, s).size());
    }

    @Test void ordinaryWordsLikeOneItemAreNotTreatedAsInventedNumbers() {
        var s = snapshot("5", null);
        String ok = "{\"conclusions\":[{\"text\":\"第一个要关注的是回款\",\"refs\":[\"sales.mom\"]}]}";
        assertEquals(1, codec.parseModelOutput(ok, s).size());
        String bad = "{\"conclusions\":[{\"text\":\"销售额涨了三成\",\"refs\":[\"sales.mom\"]}]}";
        assertThrows(IllegalArgumentException.class, () -> codec.parseModelOutput(bad, s));
    }

    @Test void developerTokensInNarrativeAreRejected() {
        var s = snapshot("5", null);
        String bad = "{\"conclusions\":[{\"text\":\"indicator_code 显示增长\",\"refs\":[\"sales.mom\"]}]}";
        assertThrows(IllegalArgumentException.class, () -> codec.parseModelOutput(bad, s));
        String bad2 = "{\"conclusions\":[{\"text\":\"见 sales.mom 的变化\",\"refs\":[\"sales.mom\"]}]}";
        assertThrows(IllegalArgumentException.class, () -> codec.parseModelOutput(bad2, s));
    }

    @Test void renderedBodyUsesBusinessWordsRoundsPercentAndHasFollowUp() {
        var insights = List.of(new Insight(InsightKind.REVIEW_RECORDED_VALUE, "sales.mom", Action.REVIEW_SALES));
        String text = codec.render(snapshot("5", insights), true);
        assertTrue(text.contains("环比：8%"), text);
        assertFalse(text.contains("8.224"), text);
        assertTrue(text.contains("覆盖率 33.3%"), text);
        assertTrue(text.contains("## 跟进分析"), text);
        assertTrue(text.contains("环比：复核销售结构与比较期间。"), text);
        assertTrue(text.contains("业务员销售业绩"), text);
        assertTrue(text.contains("应收账龄"), text);
        for (String dev : List.of("salesperson-performance", "sales-preset", "ar-ap/aging", "as of ", "POSTED-only", "sales.mom", "indicator_code"))
            assertFalse(text.contains(dev), dev + " leaked:\n" + text);
    }

    @Test void zeroOrderPeriodShowsExplicitNoticeAndPreviewLine() {
        var s = snapshot("0", List.of());
        String text = codec.render(s, true);
        assertTrue(text.contains(OperatingReportCodec.NO_DATA_NOTICE), text);
        assertEquals("该期间没有销售订单，各项销售数据为 0，建议换一个有订单的期间。", codec.previewLine(s));
        assertNull(codec.previewLine(snapshot("5", List.of())));
    }

    private Metric m(String id, String v, String unit) { return new Metric(id, id, new BigDecimal(v), unit, null, null, "x"); }

    /** LSM-COSTBI-A3: F006 报告 14748 形状的数值 + 模型多写单位的输出。 */
    @Test void narrativeRoundsPercentTwoDecimalAmountsAndCollapsesDuplicateUnits() {
        var metrics = java.util.Map.of(
                "mom", m("mom", "8.224", "%"), "yoy", m("yoy", "33.646", "%"), "rate", m("rate", "48.157", "%"),
                "amt", m("amt", "118866.6875", "元"), "inv", m("inv", "1997.81875", "元"), "x", m("x", "40572", "元"));
        assertEquals("环比 8%，同比 34%，毛利率 48%。",
                OperatingReportCodec.fillNarrative("环比 {{mom}}，同比 {{yoy}}%，毛利率 {{rate}} %。", metrics).orElseThrow());
        assertEquals("销售 118866.69元，存货 1997.82元，成本 40572元。",
                OperatingReportCodec.fillNarrative("销售 {{amt}}，存货 {{inv}}元，成本 {{x}} 元。", metrics).orElseThrow());
        assertEquals("8%", OperatingReportCodec.fillNarrative("{{mom}}%%", metrics).orElseThrow());
    }

    @Test void structuredSectionAndPreviewUseSameFormat() {
        var metrics = List.of(m("a", "118866.6875", "元"), m("b", "54.599", "%"));
        var s = new OperatingReportSnapshot(SCHEMA, Status.SUCCESS, "monthly", "2026-10-01", "2026-10-31", "2026-11-01T00:00:00Z",
                List.of(), List.of(new Section("sales", "销售", true, "x", metrics)),
                List.of(new Insight(InsightKind.REVIEW_RECORDED_VALUE, "b", Action.REVIEW_SALES)), null);
        String text = codec.render(s, true);
        assertTrue(text.contains("a：118866.69元"), text);
        assertTrue(text.contains("b：55%"), text);
        assertFalse(text.contains("%%"), text);
        var withConclusion = s.withInsights(codec.parseModelOutput(
                "{\"conclusions\":[{\"text\":\"毛利率 {{b}}% 需关注\",\"refs\":[\"b\"]}]}", s));
        String preview = codec.previewLine(withConclusion);
        assertEquals(-1, String.valueOf(preview).indexOf("%%"), preview);
        assertTrue(String.valueOf(preview).contains("55%"), preview);
    }
}
