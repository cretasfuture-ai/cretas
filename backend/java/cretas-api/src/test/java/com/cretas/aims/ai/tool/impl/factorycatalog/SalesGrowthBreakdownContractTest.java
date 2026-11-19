package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.execution.PhraseShortcutEligibility;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP 问题 47 (LSMDEMO 2026-11-18): 「10 月比 9 月涨了 8.2%，主要是哪个客户、哪个产品带来的？」原来落到要 indicator_code
 * 的指标 Skill 被追问「请告诉我要查哪个指标」。这里走识别短语表 + 短语快捷放行 + 工具真实入口 execute(ToolCall)。
 */
class SalesGrowthBreakdownContractTest {

    private static final String SAID = "10 月比 9 月涨了 8.2%，主要是哪个客户、哪个产品带来的？";
    private final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build(); // Boot 注入 mapper 的同一套默认 (JSR310 等), 不自己 new

    @ParameterizedTest
    @ValueSource(strings = {SAID, "10月比9月涨了，主要是哪个客户带来的", "十月比九月涨了，哪个产品带来的", "10月份比9月份增长主要是哪些客户贡献的"})
    void growthAttributionQuestionsAreRoutedAndNotClarified(String q) {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(q, "FACTORY");
        assertTrue(hit.isPresent(), q);
        assertEquals("PRODUCT_SALES_RANKING", hit.get().getValue(), q);
        assertTrue(PhraseShortcutEligibility.isUnconditionedSimple(q, hit.get().getKey(), hit.get().getValue()), q);
    }

    @Test
    void rankingOfCustomersQuestionIsNotHijackedByGrowthPhrases() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        // 阴性对照: 「10 月各客户销售额排行」在 prod 已能答, 不得被增量拆分短语截走
        var hit = kb.matchPhraseHit("各客户销售额排行", "FACTORY");
        assertTrue(hit.isEmpty() || !IntentKnowledgeBase.GROWTH_BREAKDOWN_PHRASES.contains(hit.get().getKey()));
        assertNull(ProductSalesRankingTool_growthPeriods("10月各客户销售额排行"));
        assertNull(ProductSalesRankingTool_growthPeriods("10月比9月的销售订单"));
    }

    private static LocalDate[] ProductSalesRankingTool_growthPeriods(String said) {
        return ProductSalesRankingTool.growthPeriods(said, LocalDate.of(2026, 11, 18));
    }

    @Test
    void monthsAreParsedCurrentVersusBase() {
        LocalDate[] p = ProductSalesRankingTool.growthPeriods(SAID, LocalDate.of(2026, 11, 18));
        assertArrayEquals(new LocalDate[]{LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)}, p);
        // 跨年: 1 月比 12 月 → 基期是去年 12 月
        LocalDate[] y = ProductSalesRankingTool.growthPeriods("1月比12月涨了多少", LocalDate.of(2026, 11, 18));
        assertEquals(LocalDate.of(2025, 12, 1), y[2]);
    }

    @Test
    void toolSplitsTheIncreaseByCustomerAndByProductWithRealEntry() throws Exception {
        ProductSalesRankingService ranking = mock(ProductSalesRankingService.class);
        LocalDate octS = LocalDate.of(2026, 10, 1), octE = LocalDate.of(2026, 10, 31);
        LocalDate sepS = LocalDate.of(2026, 9, 1), sepE = LocalDate.of(2026, 9, 30);
        List<SalesOrderItemRepository.SalesRankingRow> octRows = List.of(item("鸡腿", "300"), item("牛肉", "200"));
        List<SalesOrderItemRepository.SalesRankingRow> sepRows = List.of(item("鸡腿", "100"), item("牛肉", "250"));
        when(ranking.rank(eq("F1"), eq(octS), eq(octE))).thenReturn(octRows);
        when(ranking.rank(eq("F1"), eq(sepS), eq(sepE))).thenReturn(sepRows);
        SalesService sales = mock(SalesService.class);
        when(sales.getSalesOrdersFiltered(eq("F1"), isNull(), eq(octS), eq(octE), eq(1), anyInt()))
                .thenReturn(PageResponse.of(List.of(order("甲", "400"), order("乙", "100")), 1, 100, 2L));
        when(sales.getSalesOrdersFiltered(eq("F1"), isNull(), eq(sepS), eq(sepE), eq(1), anyInt()))
                .thenReturn(PageResponse.of(List.of(order("甲", "100"), order("乙", "250")), 1, 100, 2L));
        ProductSalesRankingTool tool = new ProductSalesRankingTool(ranking);
        ReflectionTestUtils.setField(tool, "salesService", sales);
        PriceFieldResponseAdvice view = mock(PriceFieldResponseAdvice.class);
        when(view.showsKey(any(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(tool, "priceView", view);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);

        // 带着 startDate/endDate (预处理只给出其中一个月) 也必须按原话里的两个月算
        String args = mapper.writeValueAsString(Map.of("startDate", "2026-10-01", "endDate", "2026-10-31",
                "originalUserInput", SAID));
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t", "product_sales_ranking", args),
                Map.of("factoryId", "F1", "userId", 1L)));
        assertTrue(r.path("success").asBoolean(true), r.toString());
        JsonNode d = r.has("data") ? r.get("data") : r;
        assertEquals("GROWTH_BREAKDOWN", d.path("mode").asText(), r.toString());
        // 客户: 甲 +300, 乙 -150; 总 500 -> 350 (+150 => 总增 42.9%)
        JsonNode cust = d.path("byCustomer").path("top");
        assertEquals("甲", cust.get(0).path("customerName").asText());
        assertEquals(0, new BigDecimal("300").compareTo(cust.get(0).path("delta").decimalValue()));
        assertEquals(200.0, cust.get(0).path("sharePct").asDouble(), 0.01);
        assertEquals("乙", cust.get(1).path("customerName").asText());
        assertEquals(42.9, d.path("byCustomer").path("totalGrowthPct").asDouble(), 0.01);
        // 产品: 鸡腿 +200, 牛肉 -50
        JsonNode prod = d.path("byProduct").path("top");
        assertEquals("鸡腿", prod.get(0).path("productName").asText());
        assertEquals(0, new BigDecimal("200").compareTo(prod.get(0).path("delta").decimalValue()));
        String msg = d.path("message").asText();
        assertTrue(msg.contains("8.2%") && msg.contains("仅作参照") && msg.contains("口径") && msg.contains("42.9%"), msg);
        assertFalse(msg.contains("请告诉我"), msg);
    }

    private SalesOrderItemRepository.SalesRankingRow item(String name, String amount) {
        var row = mock(SalesOrderItemRepository.SalesRankingRow.class);
        when(row.getProductTypeId()).thenReturn(name);
        when(row.getProductName()).thenReturn(name);
        when(row.getAmount()).thenReturn(new BigDecimal(amount));
        return row;
    }

    private SalesOrder order(String customer, String amount) {
        SalesOrder o = new SalesOrder();
        o.setCustomerName(customer);
        o.setTotalAmount(new BigDecimal(amount));
        o.setStatus(SalesOrderStatus.CONFIRMED);
        return o;
    }
}
