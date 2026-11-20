package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.TimeRangeFilter;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 销售数字专员 A/B: 「最近有哪些销售订单？按客户汇总一下」「本月销售订单按客户汇总，哪个客户下单最多？」
 * 修前: 默认最近 7 天没订单就回「没有记录」(同页卡片却是 28 单)、「按客户汇总」被忽略。
 */
class OrderListWorkdeskContractTest {

    private static SalesOrder order(String no, String customer, String date, String amount) {
        SalesOrder o = new SalesOrder();
        o.setId("id-" + no);
        o.setOrderNumber(no);
        o.setCustomerName(customer);
        o.setOrderDate(LocalDate.parse(date));
        o.setTotalAmount(new BigDecimal(amount));
        return o;
    }

    private static PageResponse<SalesOrder> page(List<SalesOrder> rows) {
        return PageResponse.of(rows, 1, 100, (long) rows.size());
    }

    private final SalesService salesService = mock(SalesService.class);
    private final OrderListTool tool = new OrderListTool(salesService);

    @Test
    void defaultWindowEmptyWidensToLatestOrderDateAndSaysSo() {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(6);
        List<SalesOrder> demo = List.of(order("SO-1", "甲", "2026-11-05", "100"), order("SO-2", "乙", "2026-11-04", "50"));
        when(salesService.getSalesOrdersFiltered(eq("F"), any(), eq(from), eq(today), anyInt(), anyInt()))
                .thenReturn(page(List.of()));
        when(salesService.getSalesOrders("F", 1, 100)).thenReturn(page(demo));
        when(salesService.getSalesOrdersFiltered(eq("F"), any(), eq(LocalDate.parse("2026-11-05").minusDays(29)),
                eq(LocalDate.parse("2026-11-05")), anyInt(), anyInt())).thenReturn(page(demo));

        Map<String, Object> params = new HashMap<>(Map.of("startDate", from.toString(), "endDate", today.toString(),
                TimeRangeFilter.DEFAULT_WINDOW, Boolean.TRUE));
        Map<String, Object> result = tool.doExecute("F", params, Map.of());

        assertThat(result.get("total")).isEqualTo(2L);
        assertThat((String) result.get("windowNotice")).contains("最近一次有记录是 2026-11-05");
    }

    @Test
    void explicitWindowEmptyIsNotWidened() {
        // 阴性对照: 用户明确说的时间没订单, 如实回 0 条, 不偷偷换一段时间
        when(salesService.getSalesOrdersFiltered(eq("F"), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(page(List.of()));
        Map<String, Object> params = new HashMap<>(Map.of("startDate", "2026-10-01", "endDate", "2026-10-07"));
        Map<String, Object> result = tool.doExecute("F", params, Map.of());

        assertThat(result.get("total")).isEqualTo(0L);
        assertThat(result).doesNotContainKey("windowNotice");
        verify(salesService, never()).getSalesOrders(any(), anyInt(), anyInt());
    }

    @Test
    void summarizesByCustomerWhenSentenceAsksForIt() {
        List<SalesOrder> rows = List.of(order("SO-1", "甲", "2026-11-05", "100"), order("SO-2", "乙", "2026-11-05", "900"),
                order("SO-3", "甲", "2026-11-04", "50"));
        when(salesService.getSalesOrdersFiltered(eq("F"), any(), any(), any(), anyInt(), anyInt())).thenReturn(page(rows));

        Map<String, Object> params = new HashMap<>(Map.of("startDate", "2026-11-01", "endDate", "2026-11-30",
                "originalUserInput", "本月销售订单按客户汇总，哪个客户下单最多？"));
        Map<String, Object> result = tool.doExecute("F", params, Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) result.get("customerSummary");
        assertThat(summary).hasSize(2);
        assertThat(summary.get(0).get("customerName")).isEqualTo("甲");
        assertThat(summary.get(0).get("orderCount")).isEqualTo(2);
        assertThat((BigDecimal) summary.get(0).get("totalAmount")).isEqualByComparingTo("150");

        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("ORDER_LIST").intentCategory("CRM").status("SUCCESS").resultData(result).build());
        assertThat(reply).contains("| 甲 | 2 |").contains("下单最多的是 甲（2 单）");
        assertThat(reply).doesNotContain("periodMonths");
    }

    @Test
    void plainListSentenceDoesNotSummarize() {
        // 阴性对照: 只问「有哪些订单」不带汇总意思, 仍是订单清单
        when(salesService.getSalesOrders("F", 1, 20)).thenReturn(page(List.of(order("SO-1", "甲", "2026-11-05", "1"))));
        Map<String, Object> result = tool.doExecute("F", new HashMap<>(Map.of("originalUserInput", "查询当前销售订单")), Map.of());
        assertThat(result).doesNotContainKeys("customerSummary", "groupBy");
    }

    @Test
    void widenedWindowNoticeLeadsTheReply() {
        Map<String, Object> data = new HashMap<>();
        data.put("orders", List.of(Map.of("orderNumber", "SO-1", "customerName", "甲", "totalAmount", 100, "status", "CONFIRMED")));
        data.put("total", 1L);
        data.put("windowNotice", TimeRangeFilter.widenedNotice(7, LocalDate.parse("2026-11-05"),
                LocalDate.parse("2026-10-07"), LocalDate.parse("2026-11-05")));
        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("ORDER_LIST").intentCategory("CRM").status("SUCCESS").resultData(data).build());
        assertThat(reply).startsWith("最近 7 天内没有记录；最近一次有记录是 2026-11-05");
        assertThat(reply).contains("SO-1");
    }
}
