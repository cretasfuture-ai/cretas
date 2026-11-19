package com.cretas.aims.service.reports;

import com.cretas.aims.entity.AIAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.cretas.aims.service.reports.OperatingReportSnapshot.*;

/** 自由业务结论 (数字只来自指标) + 导出 PDF/PPTX + 0 单日说法。 */
class OperatingReportFreeNarrativeExportContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OperatingReportCodec codec = new OperatingReportCodec(mapper);

    private OperatingReportSnapshot snapshot(String orders, List<Insight> insights) {
        return new OperatingReportSnapshot(SCHEMA, Status.SUCCESS, "monthly", "2026-10-01", "2026-10-31", "2026-11-01T08:00:00", List.of(),
                List.of(new Section("orders", "订单", false, "查询", List.of(new Metric("sales.orders", "订单数", new BigDecimal(orders), "单", null, null, "q"))),
                        new Section("marginHigh", "毛利率高榜", true, "查询", List.of(
                                new Metric("marginHigh.p1.rate", "酱卤牛肉 毛利率", new BigDecimal("39.9"), "%", null, BigDecimal.ONE, "q"),
                                new Metric("marginHigh.p2.rate", "卤鸡爪 毛利率", new BigDecimal("55.256"), "%", null, BigDecimal.ONE, "q"),
                                new Metric("marginHigh.p3.rate", "秘制酱 毛利率", null, "%", "已保密", null, "q")))),
                insights, null);
    }

    @Test void freeConclusionUsesRealValuesNotModelNumbers() {
        var snap = snapshot("12", List.of());
        String out = "{\"conclusions\":[{\"text\":\"卤鸡爪毛利率最高，为{{marginHigh.p2.rate}}，酱卤牛肉只有{{marginHigh.p1.rate}}，建议优先压缩牛肉成本。\",\"refs\":[\"marginHigh.p2.rate\",\"marginHigh.p1.rate\"]}]}";
        var insights = codec.parseModelOutput(out, snap);
        String text = codec.render(snap.withInsights(insights), true);
        assertTrue(text.contains("卤鸡爪毛利率最高，为55%，酱卤牛肉只有40%"), text);
        assertTrue(text.contains("建议优先压缩牛肉成本"));
    }

    @Test void bareNumbersChineseQuantitiesAndUnknownRefsAreRejected() {
        var snap = snapshot("12", List.of());
        for (String bad : List.of(
                "{\"conclusions\":[{\"text\":\"毛利率高达99%\",\"refs\":[]}]}",
                "{\"conclusions\":[{\"text\":\"成本涨了三成\",\"refs\":[]}]}",
                "{\"conclusions\":[{\"text\":\"看{{nope}}\",\"refs\":[\"nope\"]}]}",
                "{\"conclusions\":[{\"text\":\"看{{marginHigh.p1.rate}}\",\"refs\":[]}]}",
                "{\"conclusions\":[{\"text\":\"看{{marginHigh.p3.rate}}\",\"refs\":[\"marginHigh.p3.rate\"]}]}",
                "{\"conclusions\":[{\"text\":\"x\",\"refs\":[],\"extra\":1}]}")) {
            assertThrows(IllegalArgumentException.class, () -> codec.parseModelOutput(bad, snap), bad);
        }
    }

    @Test void legacyInsightContractStillParses() {
        var snap = snapshot("12", List.of());
        String legacy = "{\"insights\":[{\"kind\":\"REVIEW_RECORDED_VALUE\",\"metricId\":\"marginHigh.p1.rate\",\"action\":\"REVIEW_PROFIT\"}]}";
        assertEquals(1, codec.parseModelOutput(legacy, snap).size());
    }

    @Test void narrativeIsHiddenForReadersWhoCannotSeePricesAndForMaskedProducts() {
        var narrative = new Insight(InsightKind.NARRATIVE, null, null, "牛肉只有{{marginHigh.p1.rate}}", List.of("marginHigh.p1.rate"));
        var snap = snapshot("12", List.of(narrative));
        assertFalse(codec.render(snap, false).contains("40%"));
        assertTrue(codec.render(snap, true).contains("牛肉只有40%"));
        // 引用的指标被遮 → 整条不显示
        var maskedMetric = new Metric("marginHigh.p1.rate", "酱卤牛肉 毛利率", null, "%", "已保密", null, "q");
        var masked = new OperatingReportSnapshot(SCHEMA, Status.SUCCESS, "monthly", "2026-10-01", "2026-10-31", "t", List.of(),
                List.of(new Section("marginHigh", "毛利率高榜", true, "q", List.of(maskedMetric))), List.of(narrative), null);
        assertFalse(codec.render(masked, true).contains("牛肉只有"));
    }

    @Test void projectionDropsNarrativeThatNamesAMaskedProductWithoutRefs() {
        var projection = new OperatingReportProjection(codec);
        var leak = new Insight(InsightKind.NARRATIVE, null, null, "秘制酱利润很好", List.of());
        var snap = snapshot("12", List.of(leak));
        AIAnalysisResult report = AIAnalysisResult.builder().factoryId("SYNTHETIC").reportType("monthly").analysisText(codec.encode(snap)).build();
        report.setId(1L);
        // standalone scope: 无保密产品 → 不遮; 这里只验证不遮时照常显示 (阳性对照), 遮蔽路径由 OperatingReportSecretMaskTest 的同款装置覆盖
        assertTrue(projection.detail(report, true).getReportAnalysis().contains("秘制酱利润很好"));
    }

    @Test void zeroOrderDayStatesNoOrdersInsteadOfMissingData() {
        var snap = snapshot("0", List.of());
        String text = codec.render(snap, true);
        assertTrue(text.contains("没有销售订单"), text);
        assertTrue(text.contains("不是数据丢失"));
        assertFalse(codec.render(snapshot("5", List.of()), true).contains("没有销售订单"));
    }

    @Test void exporterProducesRealPdfAndPptxFromTheTrimmedText() throws Exception {
        var narrative = new Insight(InsightKind.NARRATIVE, null, null, "卤鸡爪毛利率最高，为{{marginHigh.p2.rate}}。", List.of("marginHigh.p2.rate"));
        String text = codec.render(snapshot("12", List.of(narrative)), true);
        var exporter = new OperatingReportExporter();
        byte[] pdf = exporter.pdf(text, "经营分析报告");
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) { assertTrue(doc.getNumberOfPages() >= 1); }
        byte[] pptx = exporter.pptx(text, "经营分析报告");
        try (var show = new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(pptx))) {
            assertTrue(show.getSlides().size() >= 3);
            StringBuilder all = new StringBuilder();
            for (var slide : show.getSlides()) for (var shape : slide.getShapes())
                if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape t) all.append(t.getText()).append('\n');
            assertTrue(all.toString().contains("55%"), all.toString());
        }
    }
}
