package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.FinanceReviewStatusGuard;
import java.time.LocalDate;
import com.cretas.aims.ai.tool.TimeRangeFilter;
import com.cretas.aims.ai.tool.StatusFilter;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists canonical sales orders. The historical work_orders table is not consulted. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderListTool extends AbstractBusinessTool {

    private final SalesService salesService;

    @Override
    public String getToolName() {
        return "order_list";
    }

    @Override
    public String getDescription() {
        return "查询销售订单列表，数据来自 sales_orders；支持分页和销售订单状态筛选。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("page", Map.of(
                "type", "integer",
                "description", "页码（从1开始）",
                "minimum", 1,
                "default", 1));
        properties.put("size", Map.of(
                "type", "integer",
                "description", "每页数量",
                "minimum", 1, "maximum", 100,
                "default", 20));
        properties.put("status", StatusFilter.property("销售订单状态，例如 DRAFT、CONFIRMED、PROCESSING、COMPLETED、CANCELLED", StatusFilter.wordsOf(SalesOrderStatus.class, SalesOrderStatus::getDisplayName, java.util.Map.of(
                SalesOrderStatus.PENDING_FINANCE_REVIEW, List.of("待审核"),
                SalesOrderStatus.FINANCE_APPROVED, List.of("财务通过"),
                SalesOrderStatus.FINANCE_REJECTED, List.of("财务驳回"),
                SalesOrderStatus.PROCESSING, List.of("备货中", "拣货中"),
                SalesOrderStatus.SHORT_CLOSED, List.of("短交")))));
        // 订单日期 (含当天): 「上个月的销售订单」以前把时间丢了, 返回全部订单
        properties.put("startDate", TimeRangeFilter.dateProperty("订单日期开始 (含), YYYY-MM-DD"));
        properties.put("endDate", TimeRangeFilter.dateProperty("订单日期结束 (含), YYYY-MM-DD"));
        return Map.of(
                "type", "object", "x-llm-extract-optional", true,
                "properties", properties,
                "required", Collections.emptyList());
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId, Map<String, Object> params, Map<String, Object> context) {
        int page = Math.max(1, getInteger(params, "page", 1));
        int size = Math.max(1, Math.min(100, getInteger(params, "size", 20)));
        String statusValue = getString(params, "status");

        // 待财务审核: 与 SalesController 同一道二次拦截 (#736), 工具路径上 controller 不运行
        FinanceReviewStatusGuard.check(statusValue, getUserRole(context), "销售单");
        LocalDate from = TimeRangeFilter.parseDate(params.get("startDate"), "startDate");
        LocalDate to = TimeRangeFilter.parseDate(params.get("endDate"), "endDate");

        PageResponse<SalesOrder> orders;
        if ((statusValue == null || statusValue.isBlank()) && from == null && to == null) {
            orders = salesService.getSalesOrders(factoryId, page, size);
        } else {
            SalesOrderStatus status = null;
            if (statusValue != null && !statusValue.isBlank()) {
                try {
                    status = SalesOrderStatus.valueOf(statusValue.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("不支持的销售订单状态: " + statusValue);
                }
            }
            // 状态 / 日期都在数据库里、分页之前过滤, 并按 DataScope 收窄 (getSalesOrdersByStatus 不收窄)
            orders = salesService.getSalesOrdersFiltered(factoryId, status, from, to, page, size);
        }

        log.info("查询销售订单列表 - factoryId={}, page={}, size={}, status={}",
                factoryId, page, size, statusValue);
        Map<String, Object> result = new HashMap<>();
        result.put("orders", orders.getContent());
        result.put("total", orders.getTotalElements());
        result.put("page", orders.getPage());
        result.put("size", orders.getSize());
        result.put("totalPages", orders.getTotalPages());
        result.put("source", "sales_orders");
        result.put("message", "查询到 " + orders.getTotalElements() + " 个销售订单");
        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * S6 gated-data rule (2026-10-29): the AI / Skill path admits exactly who the direct API admits
     * for the same data; pinned by GatedDataToolAiPathPermissionTest.
     */
    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("sales:read_write", "sales:read", "finance:read");
    }
}
