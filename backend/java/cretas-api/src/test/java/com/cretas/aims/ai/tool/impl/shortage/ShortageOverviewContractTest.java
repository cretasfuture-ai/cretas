package com.cretas.aims.ai.tool.impl.shortage;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.ResultFormatterServiceImpl;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.orchestration.InventoryMatchingService;
import com.cretas.aims.service.shortage.ShortageAnalysisService;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import com.cretas.aims.service.shortage.impl.ShortageAnalysisServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺料分析不给订单号 = 看全厂在途订单哪些缺料、缺什么 (2026-11-18 工作台答非所问)。
 * 总览读的是全厂销售订单: 有销售/财务/采购/生产读权限才给, 无 sales:read 不出客户名称; 带订单号的单订单分析不受影响。
 */
class ShortageOverviewContractTest {

    private final ShortageAnalysisService analysis = mock(ShortageAnalysisService.class);
    private final PermissionService perms = mock(PermissionService.class);
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

    private void wire() {
        ReflectionTestUtils.setField(tool, "shortageService", analysis);
        ReflectionTestUtils.setField(tool, "permissionService", perms);
        when(perms.roleHasPermission("F", "production_manager", "production:read")).thenReturn(true);
        when(perms.roleHasPermission("F", "sales_manager", "sales:read")).thenReturn(true);
    }

    private MaterialShortfall beef() {
        return new MaterialShortfall("m1", "牛腱", new BigDecimal("100"), new BigDecimal("40"), new BigDecimal("60"), "kg");
    }

    @Test
    void listsOpenOrdersWithShortageAndNamesMaterialAndQuantity() throws Exception {
        wire();
        when(analysis.listOpenOrdersForOverview("F")).thenReturn(List.of(
                order("b", "SO-B", SalesOrderStatus.CONFIRMED, "2026-11-19"),
                order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20")));
        when(analysis.analyzeForSalesOrder("F", "a")).thenReturn(report("a", beef()));
        when(analysis.analyzeForSalesOrder("F", "b")).thenReturn(report("b"));

        Map<String, Object> result = tool.doExecute("F", new HashMap<>(), Map.of("userRole", "production_manager"));

        assertThat(result.get("openOrderCount")).isEqualTo(2);
        assertThat(result.get("failedOrderCount")).isEqualTo(0);
        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("SHORTAGE_ANALYSIS").intentCategory("DATA_QUERY").status("SUCCESS").resultData(result).build());
        assertThat(reply).contains("SO-A").contains("牛腱 60kg").doesNotContain("SO-B");
        assertThat(reply).doesNotContain("trace").doesNotContain("排产建议");
    }

    @Test
    void failedAnalysisIsCountedNotTreatedAsNoShortage() throws Exception {
        wire();
        when(analysis.listOpenOrdersForOverview("F")).thenReturn(List.of(order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20")));
        when(analysis.analyzeForSalesOrder("F", "a")).thenThrow(new IllegalStateException("boom"));

        Map<String, Object> result = tool.doExecute("F", new HashMap<>(), Map.of("userRole", "production_manager"));

        assertThat(result.get("failedOrderCount")).isEqualTo(1);
        String reply = new ResultFormatterServiceImpl().format(IntentExecuteResponse.builder()
                .intentCode("SHORTAGE_ANALYSIS").intentCategory("DATA_QUERY").status("SUCCESS").resultData(result).build());
        assertThat(reply).contains("1 个订单分析失败");
    }

    @Test
    void singleOrderStillUsesTheSingleOrderPathForAnyRole() throws Exception {
        // 阴性对照: 给了订单号仍走原来的单订单分析, 不看总览权限 (仓管/质检带订单号的原有用法不受影响)
        wire();
        when(analysis.analyzeForSalesOrder("F", "SO-1")).thenReturn(report("SO-1"));
        Map<String, Object> result = tool.doExecute("F", new HashMap<>(Map.of("salesOrderId", "SO-1")),
                Map.of("userRole", "warehouse_worker"));
        assertThat(result).containsKey("shortage").doesNotContainKey("orderShortages");
    }

    @Test
    void overviewIsDeniedForRolesWithoutAnyOrderReadPermissionAndDoesNotTouchOrders() throws Exception {
        wire();
        Map<String, Object> noPerm = tool.doExecute("F", new HashMap<>(), Map.of("userRole", "warehouse_worker"));
        assertThat(noPerm.get("status")).isEqualTo("NEED_MORE_INFO");
        assertThat(noPerm.get("message").toString()).contains("订单号");
        assertThat(tool.doExecute("F", new HashMap<>(), Map.of()).get("status")).isEqualTo("NEED_MORE_INFO");
        verify(analysis, never()).listOpenOrdersForOverview("F");
    }

    @Test
    void overviewHidesCustomerNameFromRolesWithoutSalesRead() throws Exception {
        wire();
        when(analysis.listOpenOrdersForOverview("F")).thenReturn(List.of(order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20")));
        when(analysis.analyzeForSalesOrder("F", "a")).thenReturn(report("a", beef()));

        String asProduction = tool.doExecute("F", new HashMap<>(), Map.of("userRole", "production_manager"))
                .get("orderShortages").toString();
        assertThat(asProduction).contains("SO-A").contains("牛腱").contains("2026-11-20").doesNotContain("甲客户");
        // 阳性对照: 销售角色才出客户名称
        assertThat(tool.doExecute("F", new HashMap<>(), Map.of("userRole", "sales_manager")).get("orderShortages").toString())
                .contains("甲客户");
    }

    @Test
    void toolLevelPermissionsAreUnchangedSoSingleOrderUsersAreNotLockedOut() {
        assertThat(tool.getRequiredPermissions()).contains("warehouse:read", "quality:read", "production:read", "sales:read");
    }

    @Test
    void serviceListsOnlyOpenOrdersSortedByDueDate() {
        ShortageAnalysisServiceImpl service = new ShortageAnalysisServiceImpl(
                mock(InventoryMatchingService.class), mock(BomExpansionService.class));
        SalesService sales = mock(SalesService.class);
        ReflectionTestUtils.setField(service, "salesService", sales);
        when(sales.getSalesOrders("F", 1, 100)).thenReturn(PageResponse.of(List.of(
                order("a", "SO-A", SalesOrderStatus.CONFIRMED, "2026-11-20"),
                order("b", "SO-B", SalesOrderStatus.CONFIRMED, "2026-11-19"),
                order("c", "SO-C", SalesOrderStatus.COMPLETED, "2026-11-01"),
                order("d", "SO-D", SalesOrderStatus.CANCELLED, "2026-11-01")), 1, 100, 4L));

        assertThat(service.listOpenOrdersForOverview("F")).extracting(SalesOrder::getOrderNumber)
                .containsExactly("SO-B", "SO-A");
    }
}
