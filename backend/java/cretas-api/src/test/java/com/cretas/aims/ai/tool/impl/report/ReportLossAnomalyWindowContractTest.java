package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.yield.YieldAnalysisService;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import com.cretas.aims.service.yield.balance.MaterialBalanceThresholdProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP 问题 28 (2026-11-19): 「最近有哪些损耗异常」被全局预处理固定成含今天 7 天 (11-13 ~ 11-19), 而 F006 最后一天
 * YIELD 报工是 11-05, 回答成「没有损耗异常」(其实是没数据)。走工具真实入口 execute, 原句原样; 仓库桩按日期过滤报工
 * (与真实 SQL 的 report_date BETWEEN 一致), 物料平衡走真实 MaterialBalanceService + Calculator。
 * 今天 = 2026-11-19; 报工: 11-05 两个超阈值批次 + 一个平衡批次, 11-01 一个平衡批次。
 */
class ReportLossAnomalyWindowContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> reports = new ArrayList<>();
    private ReportBatchYieldRankTool tool;

    private void report(String date, String batchNo, String process, String in, String out) {
        report(date, batchNo, "酱牛肉", process, in, out);
    }

    private void report(String date, String batchNo, String product, String process, String in, String out) {
        Map<String, Object> m = new HashMap<>();
        m.put("report_id", (long) reports.size() + 1);
        m.put("task_id", (long) reports.size() + 100);
        m.put("process_order", 1);
        m.put("process_id", process);
        m.put("process_name", process);
        m.put("batch_id", batchNo == null ? null : batchNo.hashCode() * 1L);
        m.put("batch_number", batchNo);
        m.put("plan_id", "PL-" + batchNo);
        m.put("plan_number", "PN-" + batchNo);
        m.put("product_name", product);
        m.put("report_date", LocalDate.parse(date));
        m.put("input_quantity", new BigDecimal(in));
        m.put("input_unit", "kg");
        m.put("output_quantity", new BigDecimal(out));
        m.put("output_unit", "kg");
        reports.add(m);
    }

    @BeforeEach
    void setUp() {
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        when(reportRepo.findYieldReportsForBalance(eq("F1"), any(), any(), anyString(), any(), any())).thenAnswer(inv -> {
            LocalDate s = inv.getArgument(1);
            LocalDate e = inv.getArgument(2);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : reports) {
                LocalDate d = (LocalDate) r.get("report_date");
                if (!d.isBefore(s) && !d.isAfter(e)) out.add(r);
            }
            return out;
        });
        ProcessSecretAggregateExclusions excl = mock(ProcessSecretAggregateExclusions.class);
        when(excl.forCurrentCaller(anyString(), anyString())).thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        MaterialBalanceThresholdProvider th = mock(MaterialBalanceThresholdProvider.class);
        when(th.forFactory(anyString())).thenReturn(MaterialBalanceCalculator.Thresholds.DEFAULT);
        MaterialBalanceService balance = new MaterialBalanceService(reportRepo, excl, th, mapper);

        tool = new ReportBatchYieldRankTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
        when(batches.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(List.of(
                ProductionBatch.builder().productName("鸡排").productTypeId("SKU-A").build(),
                ProductionBatch.builder().productName("酱牛肉").productTypeId("SKU-B").build()));
        ReflectionTestUtils.setField(tool, "batchRepository", batches);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        ReflectionTestUtils.setField(tool, "yieldAnalysisService", mock(YieldAnalysisService.class));
        ReflectionTestUtils.setField(tool, "materialBalanceService", balance);

        report("2026-11-05", "PB-021", "熟制杀菌", "100", "88");   // 12% 异常
        report("2026-11-05", "PB-022", "熟制杀菌", "100", "90");   // 10% 异常
        report("2026-11-05", "PB-023", "清洗", "100", "99");       // 平衡
        report("2026-11-01", "PB-010", "清洗", "100", "99");       // 平衡
    }

    /** 与 ToolDispatchService 一致: 预处理把原话时间换成 startDate/endDate, 原话放 userInput。 */
    private JsonNode run(String userInput, String start, String end) throws Exception {
        return run(userInput, start, end, null, null);
    }

    private JsonNode run(String userInput, String start, String end, String product, String process) throws Exception {
        var args = mapper.createObjectNode().put("userInput", userInput).put("view", "loss_anomaly");
        if (start != null) args.put("startDate", start).put("endDate", end);
        if (product != null) args.put("productName", product);
        if (process != null) args.put("processName", process);
        return mapper.readTree(tool.execute(ToolCall.of("t", "report_batch_yield_rank", args.toString()),
                Map.of("factoryId", "F1", "userId", 1L)));
    }

    private static String msg(JsonNode r) {
        return r.get("data").get("message").asText();
    }

    private static JsonNode data(JsonNode r) {
        return r.get("data").get("data");
    }

    @Test
    void bareRecent_isWidenedToThirtyDays_soTheLastReportsAreFound() throws Exception {
        JsonNode r = run("最近有哪些损耗异常", "2026-11-13", "2026-11-19");   // 全局预处理的 7 天
        assertEquals("2026-10-21", data(r).get("period").get("startDate").asText());
        assertEquals("2026-11-19", data(r).get("period").get("endDate").asText());
        assertTrue(data(r).get("windowWidened").asBoolean());
        assertEquals(2, data(r).get("abnormalCount").asInt());
        assertTrue(msg(r).contains("PB-021") && msg(r).contains("PB-022") && msg(r).contains("近 30 天"), msg(r));
        assertFalse(msg(r).contains("没有损耗异常"), msg(r));
    }

    @Test
    void bareRecent_withoutAnyDatesAtAll_isStillThirtyDays() throws Exception {
        JsonNode r = run("最近有哪些损耗异常", null, null);
        assertEquals("2026-10-21", data(r).get("period").get("startDate").asText());
        assertEquals(2, data(r).get("abnormalCount").asInt());
    }

    @Test
    void userStatedPeriods_areNotWidened() throws Exception {
        // 最近7天 (带数量)、本月、明确日期: 按用户说的走, 不放宽
        JsonNode seven = run("最近7天有哪些损耗异常", "2026-11-13", "2026-11-19");
        assertEquals("2026-11-13", data(seven).get("period").get("startDate").asText());
        assertFalse(data(seven).get("windowWidened").asBoolean());
        JsonNode month = run("本月有哪些损耗异常", "2026-11-01", "2026-11-19");
        assertEquals("2026-11-01", data(month).get("period").get("startDate").asText());
        assertEquals(2, data(month).get("abnormalCount").asInt());
        JsonNode dates = run("11月13日到19日有哪些损耗异常", "2026-11-13", "2026-11-19");
        assertEquals("2026-11-13", data(dates).get("period").get("startDate").asText());
        // 「最近」与明确期间同句: 以明确期间为准
        JsonNode mixed = run("最近本月有哪些损耗异常", "2026-11-13", "2026-11-19");
        assertEquals("2026-11-13", data(mixed).get("period").get("startDate").asText());
    }

    @Test
    void emptyWindow_isNotAnsweredAsNoLossAnomaly_butSaysNoReportsAndPointsToTheLatestOne() throws Exception {
        JsonNode r = run("11月13日到19日有哪些损耗异常", "2026-11-13", "2026-11-19");
        String m = msg(r);
        assertTrue(data(r).get("noReportsInWindow").asBoolean());
        assertEquals("2026-11-05", data(r).get("latestReportDate").asText());
        assertEquals(2, data(r).get("abnormalNearLatestReport").asInt());
        assertTrue(m.contains("没有任何报工记录") && m.contains("2026-11-05") && m.contains("2 个批次工序"), m);
        assertTrue(m.contains("本月") && m.contains("近 30 天"), m);
        assertFalse(m.contains("没有损耗异常的批次工序"), m);
    }

    @Test
    void windowWithReportsButNoneOverThreshold_saysNoLossAnomaly() throws Exception {
        JsonNode r = run("11月1日有哪些损耗异常", "2026-11-01", "2026-11-01");
        assertEquals(0, data(r).get("abnormalCount").asInt());
        assertFalse(data(r).has("noReportsInWindow") && data(r).get("noReportsInWindow").asBoolean());
        assertEquals(1, data(r).get("reportCountInWindow").asInt());
        assertTrue(msg(r).contains("没有损耗异常"), msg(r));
        assertFalse(msg(r).contains("没有任何报工记录"), msg(r));
    }

    @Test
    void otherProductReportsDoNotMakeTheTargetProductWindowNonempty() throws Exception {
        JsonNode target = run("鸡排有哪些损耗异常", "2026-11-01", "2026-11-19", "鸡排", null);
        assertTrue(data(target).path("noReportsInWindow").asBoolean(), msg(target));
        assertEquals(0, data(target).path("reportCountInWindow").asInt(-1));
        assertTrue(data(target).path("latestReportDate").isNull());
        assertFalse(msg(target).contains("有报工 "), msg(target));

        JsonNode control = run("酱牛肉有哪些损耗异常", "2026-11-01", "2026-11-19", "酱牛肉", null);
        assertEquals(4, data(control).path("reportCountInWindow").asInt(-1));
        assertEquals(2, data(control).path("abnormalCount").asInt(-1));
    }

    @Test
    void scopedHistorySkipsLaterReportsFromOtherProductsProcessesAndMissingBatches() throws Exception {
        report("2026-11-12", "OTHER-PRODUCT", "鸡排", "熟制杀菌", "100", "80");
        report("2026-11-11", "OTHER-PROCESS", "清洗", "100", "80");
        report("2026-11-10", null, "熟制杀菌", "100", "80");
        report("2026-11-04", "NEARBY-OTHER-PRODUCT", "鸡排", "熟制杀菌", "100", "80");
        report("2026-11-04", "NEARBY-OTHER-PROCESS", "清洗", "100", "80");
        report("2026-11-04", null, "熟制杀菌", "100", "80");
        report("2026-10-29", "BEFORE-THE-SEVEN-DAYS", "熟制杀菌", "100", "80");

        JsonNode r = run("酱牛肉熟制杀菌有哪些损耗异常", "2026-11-06", "2026-11-19", "酱牛肉", "熟制杀菌");
        assertTrue(data(r).path("noReportsInWindow").asBoolean(), msg(r));
        assertEquals(0, data(r).path("reportCountInWindow").asInt(-1));
        assertEquals("2026-11-05", data(r).path("latestReportDate").asText());
        assertEquals(2, data(r).path("abnormalNearLatestReport").asInt(-1));
        assertTrue(msg(r).contains("2026-11-05") && msg(r).contains("2 个批次工序"), msg(r));
    }

    @Test
    void exactProcessReportsCountEvenWhenOnlyAFuzzyProcessIsAbnormal() throws Exception {
        report("2026-11-05", "EXACT-PROCESS", "杀菌", "100", "99");
        JsonNode r = run("杀菌有哪些损耗异常", "2026-11-05", "2026-11-05", null, "杀菌");
        assertEquals(1, data(r).path("reportCountInWindow").asInt(-1));
        assertEquals(0, data(r).path("abnormalCount").asInt(-1));
        assertTrue(msg(r).contains("有报工 1 条"), msg(r));
    }

    @Test
    void noReportsAtAllInAYear_saysSo() throws Exception {
        reports.clear();
        JsonNode r = run("11月13日到19日有哪些损耗异常", "2026-11-13", "2026-11-19");
        assertTrue(data(r).get("noReportsInWindow").asBoolean());
        assertTrue(data(r).get("latestReportDate").isNull());
        assertTrue(msg(r).contains("近一年也没有报工记录"), msg(r));
    }

    @Test
    void processNameMissing_isShownAsUnlinkedNotLiteralNull() throws Exception {
        report("2026-11-06", "CLK-OLD", null, "100", "80");
        JsonNode r = run("本月有哪些损耗异常", "2026-11-01", "2026-11-19");
        assertFalse(msg(r).contains("null"), msg(r));
        assertTrue(msg(r).contains("CLK-OLD") && msg(r).contains("未关联工序"), msg(r));
        JsonNode first = data(r).get("rows").get(0);
        assertEquals("CLK-OLD", first.get("batchNumber").asText());
        assertEquals("未关联工序", first.get("processName").asText());
    }

    @Test
    void thisWeekWindowEndingInTheFuture_isClampedToToday() throws Exception {
        // 预处理的「本周」给到周日 11-22; 今天 11-19
        JsonNode r = run("本周有哪些损耗异常", "2026-11-16", "2026-11-22");
        assertEquals("2026-11-19", data(r).get("period").get("endDate").asText());
        assertTrue(msg(r).contains("2026-11-16 至 2026-11-19"), msg(r));
    }
}
