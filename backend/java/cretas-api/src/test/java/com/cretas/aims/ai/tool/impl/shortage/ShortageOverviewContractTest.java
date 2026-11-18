package com.cretas.aims.ai.tool.impl.shortage;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.shortage.ShortageAnalysisService;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产数字专员 C: 「哪些订单可能交不了货？缺什么料？」
 * 修前回「排产建议清单」, 没有订单号、没有缺料名称与数量。shortage_analyze 不给订单号 = 在途订单缺料总览。
 */
class ShortageOverviewContractTest {

    private final SalesService salesService = mock(SalesService.class);
    private final ShortageAnalysisService analysis = mock(ShortageAnalysisService.class);
    private final ShortageAnalysisTool tool = new ShortageAnalysisTool();

    private SalesOrder order(String id, String no, SalesOrderStatus status, String due) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setOrderNumber(no);
        o.setCustomerName("甲客户");
        o.setStatus(status);
        o.setRequiredDeliveryDate(LocalDate.parse(due));
        return o;
    }

    private ShortageReport report(String id, MaterialShortfall... shortfalls) {
        return ShortageReport.builder().salesOrderId(id).materialShortages(List.of(shortfalls))
                .fullySatisfied(shortfalls.length == 0).build();
    }

    @Test
    void listsOnlyOpenOrdersWithShortageAndNamesMaterialAndQuantity() throws Exception {
        ReflectionTestUtils.setField(tool, "salesService", salesService);
        ReflectionTestUtils.setField(tool, "shortageService", analysis);
        List<SalesOrder> rows = List.of(
                order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20"),
                order("b", "SO-B", SalesOrderStatus.CONFIRMED, "2026-11-19"),
                order("c", "SO-C", SalesOrderStatus.COMPLETED, "2026-11-01"),
                order("d", "SO-D", SalesOrderStatus.CANCELLED, "2026-11-01"));
        when(salesService.getSalesOrders("F", 1, 100)).thenReturn(PageResponse.of(rows, 1, 100, 4L));
        when(analysis.analyzeForSalesOrder("F", "a")).thenReturn(report("a",
                new MaterialShortfall("m1", "牛腱", new BigDecimal("100"), new BigDecimal("40"), new BigDecimal("60"), "kg")));
        when(analysis.analyzeForSalesOrder("F", "b")).thenReturn(report("b"));

        Map<String, Object> result = tool.doExecute("F", new HashMap<>(), Map.of());

        assertThat(result.get("openOrderCount")).isEqualTo(2);
        assertThat(result.get("failedOrderCount")).isEqualTo(0);
        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("SHORTAGE_ANALYSIS").intentCategory("DATA_QUERY").status("SUCCESS").resultData(result).build());
        assertThat(reply).contains("SO-A").contains("牛腱 60kg").doesNotContain("SO-B").doesNotContain("SO-C");
        assertThat(reply).doesNotContain("trace").doesNotContain("排产建议");
    }

    @Test
    void failedAnalysisIsCountedNotTreatedAsNoShortage() throws Exception {
        ReflectionTestUtils.setField(tool, "salesService", salesService);
        ReflectionTestUtils.setField(tool, "shortageService", analysis);
        when(salesService.getSalesOrders("F", 1, 100)).thenReturn(PageResponse.of(
                List.of(order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20")), 1, 100, 1L));
        when(analysis.analyzeForSalesOrder("F", "a")).thenThrow(new IllegalStateException("boom"));

        Map<String, Object> result = tool.doExecute("F", new HashMap<>(), Map.of());

        assertThat(result.get("failedOrderCount")).isEqualTo(1);
        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("SHORTAGE_ANALYSIS").intentCategory("DATA_QUERY").status("SUCCESS").resultData(result).build());
        assertThat(reply).contains("1 个订单分析失败");
    }

    @Test
    void singleOrderStillUsesTheSingleOrderPath() throws Exception {
        // 阴性对照: 给了订单号仍走原来的单订单分析 (返回 salesOrderId/shortage, 不是总览)
        ReflectionTestUtils.setField(tool, "salesService", salesService);
        ReflectionTestUtils.setField(tool, "shortageService", analysis);
        when(analysis.analyzeForSalesOrder("F", "SO-1")).thenReturn(report("SO-1"));
        Map<String, Object> result = tool.doExecute("F", new HashMap<>(Map.of("salesOrderId", "SO-1")), Map.of());
        assertThat(result).containsKey("shortage").doesNotContainKey("orderShortages");
    }

    @Test
    void overviewHidesCustomerNameFromRolesWithoutSalesReadButKeepsOrderShortageFacts() throws Exception {
        ReflectionTestUtils.setField(tool, "salesService", salesService);
        ReflectionTestUtils.setField(tool, "shortageService", analysis);
        com.cretas.aims.service.PermissionService perms = mock(com.cretas.aims.service.PermissionService.class);
        when(perms.roleHasPermission("F", "sales_manager", "sales:read")).thenReturn(true);
        ReflectionTestUtils.setField(tool, "permissionService", perms);
        when(salesService.getSalesOrders("F", 1, 100)).thenReturn(PageResponse.of(
                List.of(order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20")), 1, 100, 1L));
        when(analysis.analyzeForSalesOrder("F", "a")).thenReturn(report("a",
                new MaterialShortfall("m1", "牛腱", new BigDecimal("100"), new BigDecimal("40"), new BigDecimal("60"), "kg")));

        // 生产角色 (无 sales:read): 有订单号、交期、缺料名称与数量, 没有客户名称
        Map<String, Object> asProduction = tool.doExecute("F", new HashMap<>(), Map.of("userRole", "production_manager"));
        String rows = asProduction.get("orderShortages").toString();
        assertThat(rows).contains("SO-A").contains("牛腱").contains("2026-11-20").doesNotContain("甲客户");

        // 阴性对照: 没有角色信息同样不出客户; 销售角色才出
        assertThat(tool.doExecute("F", new HashMap<>(), Map.of()).get("orderShortages").toString()).doesNotContain("甲客户");
        assertThat(tool.doExecute("F", new HashMap<>(), Map.of("userRole", "sales_manager")).get("orderShortages").toString())
                .contains("甲客户");

        // 门槛: 生产读放行, 质检/仓储读不再放行 (总览读的是销售订单)
        assertThat(tool.getRequiredPermissions()).contains("production:read", "sales:read", "finance:read", "procurement:read")
                .doesNotContain("warehouse:read", "quality:read");
    }
}
