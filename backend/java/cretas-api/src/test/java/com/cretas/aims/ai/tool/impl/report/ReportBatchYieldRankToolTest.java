package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportBatchYieldRankToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ProductionBatch> batches = new ArrayList<>();
    private ReportBatchYieldRankTool tool;

    @BeforeEach
    void setUp() {
        ProductionBatchRepository repo = mock(ProductionBatchRepository.class);
        when(repo.findBatchesInDateRange(eq("F1"), any(), any())).thenReturn(batches);
        tool = new ReportBatchYieldRankTool() {
            @Override
            protected LocalDate today() {
                return LocalDate.of(2026, 11, 14);
            }
        };
        ReflectionTestUtils.setField(tool, "batchRepository", repo);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
    }

    private void add(String no, String product, String actual, String good, ProductionBatchStatus st) {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber(no);
        b.setProductName(product);
        b.setProductTypeId("PT-" + product);
        b.setStatus(st);
        b.setUnit("kg");
        b.setActualQuantity(actual == null ? null : new BigDecimal(actual));
        b.setGoodQuantity(good == null ? null : new BigDecimal(good));
        batches.add(b);
    }

    private JsonNode run(String args) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t", "report_batch_yield_rank", args), Map.of("factoryId", "F1", "userId", 1L)));
    }

    @Test
    void isReadOnly() {
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
    }

    @Test
    void lowestFirst_computedFromQuantities_notFromTheStoredYieldColumn() throws Exception {
        add("B1", "卤牛肉", "100", "90", ProductionBatchStatus.COMPLETED);   // 90.00
        add("B2", "卤牛肉", "80", "60", ProductionBatchStatus.COMPLETED);    // 75.00
        add("B3", "酱鸭", "50", "49", ProductionBatchStatus.COMPLETED);      // 98.00
        // 存的 yield_rate 列是冻结的旧值 100 -> 不能被采用 (历史缺陷: 列冻结)
        batches.get(1).setYieldRate(new BigDecimal("100.00"));

        JsonNode r = run("{}");
        JsonNode rows = r.get("data").get("data").get("rows");
        assertEquals("B2", rows.get(0).get("batchNumber").asText());
        assertEquals(75.00, rows.get(0).get("yieldRatePct").asDouble(), 0.001);
        assertEquals("B3", rows.get(2).get("batchNumber").asText());
        assertTrue(r.get("data").get("message").asText().contains("B2"));
        assertEquals(1, r.get("data").get("data").get("charts").size());
    }

    @Test
    void highestFirstAndLimit() throws Exception {
        add("B1", "卤牛肉", "100", "90", ProductionBatchStatus.COMPLETED);
        add("B2", "卤牛肉", "80", "60", ProductionBatchStatus.COMPLETED);
        add("B3", "酱鸭", "50", "49", ProductionBatchStatus.COMPLETED);
        JsonNode rows = run("{\"order\":\"highest\",\"limit\":1}").get("data").get("data").get("rows");
        assertEquals(1, rows.size());
        assertEquals("B3", rows.get(0).get("batchNumber").asText());
    }

    @Test
    void missingQuantitiesAndUnfinishedBatches_areCountedNotRanked() throws Exception {
        add("B1", "卤牛肉", "100", "90", ProductionBatchStatus.COMPLETED);
        add("B2", "卤牛肉", "100", null, ProductionBatchStatus.COMPLETED);      // 缺良品 -> 不是 0%/100%
        add("B3", "卤牛肉", "0", "0", ProductionBatchStatus.COMPLETED);         // 实际为 0
        add("B4", "卤牛肉", "100", "10", ProductionBatchStatus.IN_PROGRESS);    // 未完成
        JsonNode data = run("{}").get("data").get("data");
        assertEquals(1, data.get("rankedBatchCount").asInt());
        assertEquals(2, data.get("noYieldBatchCount").asInt());
        assertEquals(3, data.get("completedBatchCount").asInt());
    }

    @Test
    void nothingRankable_saysSoAndDrawsNoChart() throws Exception {
        add("B2", "卤牛肉", "100", null, ProductionBatchStatus.COMPLETED);
        JsonNode r = run("{}");
        assertTrue(r.get("data").get("message").asText().contains("没有能算出出成率的批次"));
        assertFalse(r.get("data").get("data").has("charts"));
    }

    @Test
    void productFilter() throws Exception {
        add("B1", "卤牛肉", "100", "90", ProductionBatchStatus.COMPLETED);
        add("B3", "酱鸭", "50", "49", ProductionBatchStatus.COMPLETED);
        assertEquals(1, run("{\"productName\":\"酱鸭\"}").get("data").get("data").get("rows").size());
    }
}
