package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.shortage.CostDeviationScanService;
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
 * BP 问题 28 (11-19): 「最近有哪些损耗异常」答「没有异常（5% 以内）」, 而「发现的问题」页对酱卤牛肉熟制杀菌偏离 3.7%（阈值 3%）已立单。
 * 物料平衡走真实的 MaterialBalanceService + MaterialBalanceCalculator (只有仓库和阈值是桩), 出成率聚合桩住 YieldAnalysisService。
 * 今天 = 2026-11-18, 本月 = 11-01..11-18, 上月 = 10-01..10-31。
 */
class LossAnomalyAlertThresholdContractTest {

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

        balanceRow("B-J1", "酱卤牛肉", "熟制杀菌", 2, "100", "96.3");  // 偏差 3.7%: 问题页(阈值 3%)已立单
        balanceRow("B-J2", "酱卤牛肉", "熟制杀菌", 2, "100", "97");    // 偏差 3.0%: 恰在阈值上, 不超
        balanceRow("B-J3", "酱卤牛肉", "清洗", 1, "100", "70");        // 偏差 30%: 红
    }

    private void alertThresholdIs(String balancePct) {
        CostDeviationScanService scan = mock(CostDeviationScanService.class);
        BigDecimal pct = balancePct == null ? null : new BigDecimal(balancePct);
        when(scan.effectiveThresholds("F1")).thenReturn(new CostDeviationScanService.EffectiveThresholds(
                new CostDeviationScanService.Thresholds(null, pct), pct == null ? null : "FACTORY"));
        ReflectionTestUtils.setField(tool, "costDeviationScanService", scan);
    }

    private JsonNode run(String args) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t", "report_batch_yield_rank", args),
                Map.of("factoryId", "F1", "userId", 1L)));
    }

    @Test
    void deviation3Point7WithAlertThreshold3IsListedAsAnomalyAndTheThresholdIsStated() throws Exception {
        alertThresholdIs("3");

        JsonNode r = run("{\"userInput\":\"最近有哪些损耗异常\"}");

        String msg = r.get("data").get("message").asText();
        JsonNode data = r.get("data").get("data");
        assertEquals("loss_anomaly", data.get("view").asText());
        assertEquals(2, data.get("abnormalCount").asInt(), msg);   // B-J1 (3.7%) 与 B-J3 (30%); B-J2 恰 3.0% 不超
        assertTrue(msg.contains("B-J1") && msg.contains("3.7%") && msg.contains("熟制杀菌"), msg);
        assertTrue(msg.contains("B-J3"), msg);
        assertFalse(msg.contains("B-J2"), "恰在阈值上不算超: " + msg);
        assertFalse(msg.contains("没有损耗异常"), msg);
        assertEquals(3, data.get("rows").get(1).get("standardDeviationLimit").asInt());
        assertEquals(3.7, data.get("rows").get(1).get("deviationPct").asDouble(), 0.001);
        // 回答里写明用的阈值与来源
        assertTrue(msg.contains("标准 ≤ 3%") && msg.contains("发现的问题"), msg);
        assertFalse(msg.contains("默认 5%"), "已用问题页阈值, 不再说默认 5%: " + msg);
    }

    @Test
    void withoutAnAlertThresholdTheMaterialBalanceDefaultIsUsedAndStatedAsSuch() throws Exception {
        alertThresholdIs(null);

        JsonNode r = run("{\"userInput\":\"最近有哪些损耗异常\"}");

        String msg = r.get("data").get("message").asText();
        assertEquals(1, r.get("data").get("data").get("abnormalCount").asInt(), msg);   // 只有 30% 的
        assertTrue(msg.contains("B-J3") && !msg.contains("B-J1"), msg);
        assertTrue(msg.contains("标准 ≤ 5%") && msg.contains("默认 5%"), msg);
    }

    @Test
    void theSameThresholdDecidesTheProcessViewAbnormalBatchCount() throws Exception {
        alertThresholdIs("3");

        JsonNode r = run("{\"userInput\":\"熟制杀菌最近出成率怎么样\"}");

        JsonNode row = r.get("data").get("data").get("rows").get(0);
        assertEquals("熟制杀菌", row.get("processName").asText());
        assertEquals(1, row.get("abnormalBatchCount").asInt(), r.toString());   // B-J1 3.7%; B-J2 恰 3.0% 不超
    }
}
