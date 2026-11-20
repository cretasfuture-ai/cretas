package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.execution.PhraseShortcutEligibility;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import com.cretas.aims.service.inventory.SalesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BP 问题 47 补强 (11-19 10:00 F006 销售数字专员追问): 「10月比9月涨了8.2%，主要是哪个客户、哪个产品带来的」答成 15 张订单列表。
 * #1909 只认固定的几种短语 (哪个客户带来的 …), 换个说法 (拉动 / 靠 / 涨的原因 / 哪几个客户和产品) 就落到识别层; 工具又要求
 * 预处理必须给出 startDate / endDate, 没给就追问日期。这里: ① 归因问法换说法也走同一个意图 (短语快捷口径同一份判据);
 * ② 工具真实入口只凭原话里的两个月就答出拆分, 按客户、产品分别给前几名 + 占总增量比例, 不是订单列表。
 */
class SalesGrowthAttributionParaphraseContractTest {

    private final ObjectMapper mapper =
            org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "10月比9月涨了8.2%，主要是哪个客户哪个产品拉动的",
            "10月比9月涨了8.2%，主要靠哪个客户、哪个产品",
            "10月比9月涨了8.2%，涨的原因主要是哪个客户、哪个产品",
            "10月比9月涨了8.2%，主要是哪几个客户和产品带来的",
            "那10月份比9月份增长主要来自哪些客户",
            "十月比九月降了，是哪个产品导致的"})
    void paraphrasedAttributionQuestionsGoToTheGrowthBreakdownIntent(String q) {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(q.toLowerCase().trim(), "FACTORY");
        assertTrue(hit.isPresent(), "应命中增量拆分意图: " + q);
        assertEquals("PRODUCT_PRODUCT_SALES_RANKING", hit.get().getValue(), q);
        assertEquals(hit.get().getValue(), kb.matchPhrase(q.toLowerCase().trim(), "FACTORY").orElse(null), q);
        assertTrue(PhraseShortcutEligibility.isUnconditionedSimple(q, hit.get().getKey(), hit.get().getValue()), q);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10月比9月的销售订单",            // 没有涨跌词 / 归因
            "10月比9月涨了多少",              // 没问是哪个客户 / 产品
            "各客户销售额排行",               // 没有两个月比较
            "最近有哪些销售订单，按客户汇总一下"})
    void ordinaryQuestionsAreNotCapturedByTheAttributionRule(String q) {
        assertFalse(IntentKnowledgeBase.isGrowthAttribution(q), q);
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        var hit = kb.matchPhraseHit(q.toLowerCase().trim(), "FACTORY");
        assertTrue(hit.isEmpty() || !IntentKnowledgeBase.GROWTH_BREAKDOWN_PHRASES.contains(hit.get().getKey()), q);
    }

    @Test
    @DisplayName("工具真实入口: 只有原话没有 startDate/endDate 也不追问日期, 答出按客户/产品的涨因排序, 不是订单列表")
    void toolAnswersFromTheSentenceAloneWithoutDates() throws Exception {
        String said = "10月比9月涨了8.2%，主要是哪个客户哪个产品拉动的";
        ProductSalesRankingService ranking = mock(ProductSalesRankingService.class);
        LocalDate octS = LocalDate.of(2026, 10, 1), octE = LocalDate.of(2026, 10, 31);
        LocalDate sepS = LocalDate.of(2026, 9, 1), sepE = LocalDate.of(2026, 9, 30);
        var octRows = List.of(item("鸡腿", "300"), item("牛肉", "200"));
        var sepRows = List.of(item("鸡腿", "100"), item("牛肉", "250"));
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

        String args = mapper.writeValueAsString(Map.of("originalUserInput", said));
        String json = tool.execute(ToolCall.of("t", "product_sales_ranking", args),
                Map.of("factoryId", "F1", "userId", 1L));
        JsonNode r = mapper.readTree(json);

        assertFalse(json.contains("NEED_MORE_INFO"), "两个月在原话里, 不得追问日期: " + json);
        JsonNode d = r.has("data") ? r.get("data") : r;
        assertEquals("GROWTH_BREAKDOWN", d.path("mode").asText(), json);
        assertFalse(d.has("ranking"), "不是订单 / 排行清单: " + json);
        JsonNode cust = d.path("byCustomer").path("top");
        assertEquals("甲", cust.get(0).path("customerName").asText());
        assertEquals(200.0, cust.get(0).path("sharePct").asDouble(), 0.01);   // 甲 +300 占总增量 +150 的 200%
        assertEquals("乙", cust.get(1).path("customerName").asText());
        assertEquals("鸡腿", d.path("byProduct").path("top").get(0).path("productName").asText());
        assertTrue(d.path("message").asText().contains("占总增量"), json);
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
