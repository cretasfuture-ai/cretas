package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BP 问题 28: 三句原话走工具真实入口 (execute) 要答出工序维度, 不再「暂不支持损耗率」。
 * 物料平衡走真实的 MaterialBalanceService + MaterialBalanceCalculator (只有仓库和阈值是桩), 出成率聚合桩住 YieldAnalysisService。
 * 今天 = 2026-11-18, 本月 = 11-01..11-18, 上月 = 10-01..10-31。
 */
class ReportYieldProcessViewsContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Map<String, Object>> balanceRows = new ArrayList<>();
    private ReportBatchYieldRankTool tool;
    private YieldAnalysisService yield;
    private ProductionBatchRepository batchRepo;

    private static ProcessYieldAggDTO agg(String name, String rate, int batches) {
        BigDecimal r = new BigDecimal(rate);
        return ProcessYieldAggDTO.builder().processName(name)
                .inputQuantity(new BigDecimal("1000.00")).outputQuantity(r.multiply(BigDecimal.TEN).setScale(2))
                .conversionRate(r).wastageRate(new BigDecimal("100").subtract(r))
                .inputUnit("kg").outputUnit("kg").unit("kg").unitComparable(true).batchCount(batches).build();
    }

    private void balanceRow(String batchNo, String product, String process, int order, String in, String out) {
        Map<String, Object> m = new HashMap<>();
        m.put("report_id", (long) balanceRows.size() + 1);
        m.put("task_id", (long) balanceRows.size() + 100);
        m.put("process_order", order);
        m.put("process_id", process);
        m.put("process_name", process);
        m.put("batch_id", batchNo.hashCode() * 1L);
        m.put("batch_number", batchNo);
        m.put("plan_id", "PL-" + batchNo);
        m.put("plan_number", "PN-" + batchNo);
        m.put("product_name", product);
        m.put("input_quantity", new BigDecimal(in));
        m.put("input_unit", "kg");
        m.put("output_quantity", new BigDecimal(out));
        m.put("output_unit", "kg");
        balanceRows.add(m);
    }

    @BeforeEach
    void setUp() {
        ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
        when(reportRepo.findYieldReportsForBalance(eq("F1"), any(), any(), anyString(), any(), any())).thenReturn(balanceRows);
        ProcessSecretAggregateExclusions excl = mock(ProcessSecretAggregateExclusions.class);
        when(excl.forCurrentCaller(anyString(), anyString())).thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        MaterialBalanceThresholdProvider th = mock(MaterialBalanceThresholdProvider.class);
        when(th.forFactory(anyString())).thenReturn(MaterialBalanceCalculator.Thresholds.DEFAULT);
        MaterialBalanceService balance = new MaterialBalanceService(reportRepo, excl, th, mapper);

        yield = mock(YieldAnalysisService.class);
        when(yield.aggregateByProcess(eq("F1"), any(), any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(1);
            if (start.equals(LocalDate.of(2026, 11, 1)) || start.equals(LocalDate.of(2026, 10, 20))) {
                // 本月 / 默认近 30 天
                return List.of(agg("清洗", "94.0", 12), agg("熟制杀菌", "80.0", 12), agg("包装", "99.0", 11));
            }
            if (start.equals(LocalDate.of(2026, 10, 1)) || start.equals(LocalDate.of(2026, 9, 20))) {
                // 上月 / 上一段同长窗口
                return List.of(agg("清洗", "95.0", 10), agg("熟制杀菌", "90.0", 10), agg("包装", "99.0", 10));
            }
            // 一年窗口 (只用来认原话里的工序名)
            return List.of(agg("清洗", "95.0", 40), agg("熟制杀菌", "90.0", 40), agg("包装", "99.0", 40));
        });

        batchRepo = mock(ProductionBatchRepository.class);
        tool = new ReportBatchYieldRankTool() {
            @Override
            protected LocalDate today() {
                return LocalDate.of(2026, 11, 18);
            }
        };
        ReflectionTestUtils.setField(tool, "batchRepository", batchRepo);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        ReflectionTestUtils.setField(tool, "yieldAnalysisService", yield);
        ReflectionTestUtils.setField(tool, "materialBalanceService", balance);

        balanceRow("B1", "酱牛肉", "熟制杀菌", 2, "100", "70");   // 偏差 30% 红
        balanceRow("B2", "酱牛肉", "熟制杀菌", 2, "100", "97");   // 偏差 3% 平衡
        balanceRow("B3", "酱鸭", "清洗", 1, "100", "88");        // 偏差 12% 黄
        balanceRow("B4", "酱鸭", "熟制杀菌", 2, "100", "92");     // 偏差 8% 黄
    }

    private JsonNode run(String args) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t", "report_batch_yield_rank", args), Map.of("factoryId", "F1", "userId", 1L)));
    }

    private static String q(String text) {
        return "{\"userInput\":\"" + text + "\"}";
    }

    @Test
    void lossAnomaly_listsBatchesWithProcessStandardActualAndDeviation() throws Exception {
        JsonNode r = run(q("最近有哪些损耗异常"));
        String msg = r.get("data").get("message").asText();
        JsonNode data = r.get("data").get("data");
        assertFalse(msg.contains("暂不支持"), msg);
        assertEquals("loss_anomaly", data.get("view").asText());
        assertEquals(3, data.get("abnormalCount").asInt());
        JsonNode rows = data.get("rows");
        // 偏差大的在前, 批次号原样, 带工序
        assertEquals("B1", rows.get(0).get("batchNumber").asText());
        assertEquals("熟制杀菌", rows.get(0).get("processName").asText());
        assertEquals("酱牛肉", rows.get(0).get("productName").asText());
        assertEquals(30.0, rows.get(0).get("deviationPct").asDouble(), 0.001);
        assertEquals(5, rows.get(0).get("standardDeviationLimit").asInt());
        assertEquals("B3", rows.get(1).get("batchNumber").asText());
        assertEquals("清洗", rows.get(1).get("processName").asText());
        assertTrue(msg.contains("B1") && msg.contains("B3") && msg.contains("B4") && msg.contains("标准 ≤ 5%") && msg.contains("实际偏差 30%"), msg);
        assertFalse(msg.contains("B2"), "平衡的批次不是异常: " + msg);
        assertTrue(msg.contains("口径"), msg);
    }

    @Test
    void processYield_fuzzyProcessNameFromTheSentence_hasMeanTrendAndAbnormalBatchCount() throws Exception {
        JsonNode r = run(q("熟制杀菌最近出成率怎么样"));
        String msg = r.get("data").get("message").asText();
        JsonNode data = r.get("data").get("data");
        assertEquals("process", data.get("view").asText());
        JsonNode rows = data.get("rows");
        assertEquals(1, rows.size(), "只留被问到的工序");
        JsonNode row = rows.get(0);
        assertEquals("熟制杀菌", row.get("processName").asText());
        assertEquals(80.0, row.get("avgYieldRate").asDouble(), 0.001);
        assertEquals(20.0, row.get("avgLossRate").asDouble(), 0.001);
        assertEquals(-10.0, row.get("yieldRateChange").asDouble(), 0.001);
        assertEquals(2, row.get("abnormalBatchCount").asInt());   // B1 红 + B4 黄 (B2 平衡)
        assertTrue(msg.contains("熟制杀菌") && msg.contains("下降 10") && msg.contains("异常批次 2"), msg);
        assertFalse(msg.contains("暂不支持"), msg);
    }

    @Test
    void processYield_explicitProcessNameParamMatchesFuzzily() throws Exception {
        JsonNode r = run("{\"processName\":\"杀菌\"}");
        assertEquals("熟制杀菌", r.get("data").get("data").get("rows").get(0).get("processName").asText());
    }

    @Test
    void whyDidYieldDrop_comparesThisMonthWithLastMonth_andNamesTheWorstProcessAndItsBatches() throws Exception {
        JsonNode r = run(q("这个月出成率为什么掉了、掉在哪道工序"));
        String msg = r.get("data").get("message").asText();
        JsonNode data = r.get("data").get("data");
        assertEquals("process_compare", data.get("view").asText());
        assertEquals("2026-11-01", data.get("period").get("startDate").asText());
        assertEquals("2026-10-01", data.get("comparedWith").get("startDate").asText());
        assertEquals("2026-10-31", data.get("comparedWith").get("endDate").asText());
        assertEquals("熟制杀菌", data.get("biggestDropProcess").asText());
        assertEquals("熟制杀菌", data.get("rows").get(0).get("processName").asText());   // 下降最多排第一
        assertEquals(-10.0, data.get("rows").get(0).get("yieldRateChange").asDouble(), 0.001);
        List<String> batches = new ArrayList<>();
        data.get("biggestDropBatches").forEach(n -> batches.add(n.get("batchNumber").asText()));
        assertEquals(List.of("B1", "B4"), batches);
        assertTrue(msg.contains("掉得最多的是「熟制杀菌」") && msg.contains("B1"), msg);
        assertFalse(msg.contains("暂不支持"), msg);
    }

    @Test
    void preprocessedDatesStillCompareAgainstTheFullPreviousMonth() throws Exception {
        // 对话预处理把「这个月」换成显式日期: 仍和上个自然月比
        JsonNode r = run("{\"startDate\":\"2026-11-01\",\"endDate\":\"2026-11-18\",\"view\":\"process_compare\"}");
        assertEquals("2026-10-01", r.get("data").get("data").get("comparedWith").get("startDate").asText());
    }

    @Test
    void oldBatchQuestionsKeepTheBatchView() throws Exception {
        ProductionBatchRepository repo = mock(ProductionBatchRepository.class);
        when(repo.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(tool, "batchRepository", repo);
        // 阴性对照: 「出成率异常的批次」没有损耗 / 掉 / 工序字样, 仍是批次排行 (不进工序视图)
        JsonNode data = run(q("最近有哪些批次出成率异常")).get("data").get("data");
        assertTrue(data.has("completedBatchCount"), data.toString());
        assertFalse(data.has("view"), data.toString());
    }

    private static ProductionBatch batch(String product, String typeId) {
        ProductionBatch b = new ProductionBatch();
        b.setProductName(product);
        b.setProductTypeId(typeId);
        b.setStatus(ProductionBatchStatus.COMPLETED);
        return b;
    }

    @Test
    void productName_lossAnomalyOnlyContainsThatProductsBatches() throws Exception {
        when(batchRepo.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(List.of(batch("酱牛肉", "PT-BEEF"), batch("酱鸭", "PT-DUCK")));
        JsonNode data = run("{\"userInput\":\"酱牛肉最近有哪些损耗异常\",\"productName\":\"酱牛肉\"}").get("data").get("data");
        List<String> batches = new ArrayList<>();
        data.get("rows").forEach(n -> {
            batches.add(n.get("batchNumber").asText());
            assertEquals("酱牛肉", n.get("productName").asText());
        });
        assertEquals(List.of("B1"), batches);
        assertEquals(1, data.get("abnormalCount").asInt());
        // 对照: 不带产品名仍是全厂 3 条
        assertEquals(3, run(q("最近有哪些损耗异常")).get("data").get("data").get("abnormalCount").asInt());
    }

    @Test
    void productName_processCompareFiltersYieldAggregateByProductTypeAndBatchesByName() throws Exception {
        when(batchRepo.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(List.of(batch("酱牛肉", "PT-BEEF"), batch("酱鸭", "PT-DUCK")));
        JsonNode r = run("{\"userInput\":\"酱牛肉这个月出成率为什么掉了\",\"productName\":\"酱牛肉\"}");
        JsonNode data = r.get("data").get("data");
        assertEquals("process_compare", data.get("view").asText());
        verify(yield, atLeastOnce()).aggregateByProcess(eq("F1"), any(), any(), eq("PT-BEEF"));
        assertTrue(data.get("productFilteredYield").asBoolean());
        List<String> batches = new ArrayList<>();
        data.get("biggestDropBatches").forEach(n -> batches.add(n.get("batchNumber").asText()));
        assertEquals(List.of("B1"), batches, "酱鸭的 B4 不得出现");
        assertFalse(r.get("data").get("message").asText().contains("未按"), r.toString());
    }

    @Test
    void productName_mappingToSeveralProductTypes_saysItWasNotFilteredInsteadOfSilentlyAnsweringWholeFactory() throws Exception {
        when(batchRepo.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(List.of(batch("酱牛肉A", "PT-1"), batch("酱牛肉B", "PT-2")));
        JsonNode r = run("{\"userInput\":\"酱牛肉这个月出成率为什么掉了\",\"productName\":\"酱牛肉\"}");
        assertFalse(r.get("data").get("data").get("productFilteredYield").asBoolean());
        assertTrue(r.get("data").get("message").asText().contains("未按「酱牛肉」筛选"), r.toString());
    }

    @Test
    void productName_withoutAnyBatch_saysSoAndReturnsNoRows() throws Exception {
        JsonNode r = run("{\"userInput\":\"酱羊肉这个月出成率为什么掉了\",\"productName\":\"酱羊肉\"}");
        assertEquals(0, r.get("data").get("data").get("rows").size());
        assertTrue(r.get("data").get("message").asText().contains("没有找到名称含「酱羊肉」的批次"), r.toString());
    }

    @Test
    void overlappingProcessNames_exactMatchWinsAndAnAbnormalBatchIsCountedOnce() throws Exception {
        when(yield.aggregateByProcess(eq("F1"), any(), any(), any()))
                .thenReturn(List.of(agg("杀菌", "85.0", 3), agg("熟制杀菌", "80.0", 12)));
        balanceRow("B5", "酱牛肉", "杀菌", 3, "100", "80");   // 偏差 20% 红, 只属于「杀菌」
        JsonNode rows = run("{\"view\":\"process\"}").get("data").get("data").get("rows");
        int total = 0;
        for (JsonNode row : rows) {
            int n = row.get("abnormalBatchCount").asInt();
            total += n;
            assertEquals("杀菌".equals(row.get("processName").asText()) ? 1 : 2, n, row.toString());
        }
        assertEquals(3, total, "B1/B4/B5 各记一次");
        // 说「杀菌」且有同名工序: 只认精确的那一行
        JsonNode only = run("{\"processName\":\"杀菌\"}").get("data").get("data").get("rows");
        assertEquals(1, only.size());
        assertEquals("杀菌", only.get(0).get("processName").asText());
    }

    @Test
    void yieldAndLossRankQuestion_staysBatchRanking_notLossAnomaly() throws Exception {
        when(batchRepo.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(List.of());
        JsonNode data = run(q("出成率和损耗率最低的批次")).get("data").get("data");
        assertTrue(data.has("completedBatchCount"), data.toString());
        assertFalse(data.has("view"), data.toString());
        // 阴性对照: 带「异常」仍是损耗异常视图
        assertEquals("loss_anomaly", run(q("损耗异常的批次")).get("data").get("data").get("view").asText());
    }
}
