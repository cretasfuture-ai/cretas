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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists canonical sales orders. The historical work_orders table is not consulted. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderListTool extends AbstractBusinessTool {

    private static final int WIDENED_DAYS = 30;

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
        properties.put("groupBy", Map.of(
                "type", "string",
                "enum", List.of("customer"),
                "description", "按客户汇总 (每个客户的订单数与金额, 按订单数排名); 用户说「按客户汇总 / 各客户下单 / 哪个客户下单最多」时填 customer"));
        return Map.of(
                "type", "object",
                "properties", properties,
                // 没说时间 = 最近 7 天, 回复写明 (Steve 2026-11-12 口径 1; 只在对话只读链路上补, REST 列表不受影响)
                TimeRangeFilter.DEFAULT_RECENT_DAYS, 7,
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

        boolean byCustomer = wantsCustomerSummary(params);
        boolean defaultWindow = Boolean.TRUE.equals(params.get(TimeRangeFilter.DEFAULT_WINDOW));
        SalesOrderStatus status = null;
        if (statusValue != null && !statusValue.isBlank()) {
            try {
                status = SalesOrderStatus.valueOf(statusValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("不支持的销售订单状态: " + statusValue);
            }
        }
        // 汇总要看完整窗口内的订单 (不分页), 其余仍按页
        int querySize = byCustomer ? 100 : size;
        PageResponse<SalesOrder> orders = query(factoryId, status, from, to, byCustomer ? 1 : page, querySize);
        String windowNotice = null;
        if (defaultWindow && orders.getTotalElements() == 0 && from != null && to != null) {
            // 默认「最近 N 天」没有订单: 不回「没有记录」, 找最近一次有订单的日期, 给出以它结尾的同长度一段
            LocalDate latest = latestOrderDate(factoryId);
            if (latest != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
                // 放宽成以最近一次订单日期结尾的 30 天 (7 天可能只截到一个零头)
                LocalDate newFrom = latest.minusDays(WIDENED_DAYS - 1);
                orders = query(factoryId, status, newFrom, latest, byCustomer ? 1 : page, querySize);
                windowNotice = TimeRangeFilter.widenedNotice((int) days, latest, newFrom, latest);
                from = newFrom;
                to = latest;
            }
        }

        log.info("查询销售订单列表 - factoryId={}, page={}, size={}, status={}",
                factoryId, page, size, statusValue);
        Map<String, Object> result = new HashMap<>();
        result.put("orders", toSummaries(orders.getContent()));
        result.put("total", orders.getTotalElements());
        result.put("page", orders.getPage());
        result.put("size", orders.getSize());
        result.put("totalPages", orders.getTotalPages());
        result.put("source", "sales_orders");
        result.put("message", "查询到 " + orders.getTotalElements() + " 个销售订单");
        if (windowNotice != null) {
            result.put("windowNotice", windowNotice);
            result.put("effectiveFrom", from.toString());
            result.put("effectiveTo", to.toString());
        }
        if (byCustomer) {
            result.put("customerSummary", summarizeByCustomer(orders));
            result.put("groupBy", "customer");
            long shown = orders.getContent().size();
            if (orders.getTotalElements() > shown) {
                result.put("summaryTruncated", true);
            }
        }
        return result;
    }

    private PageResponse<SalesOrder> query(String factoryId, SalesOrderStatus status, LocalDate from, LocalDate to,
                                           int page, int size) {
        if (status == null && from == null && to == null) {
            return salesService.getSalesOrders(factoryId, page, size);
        }
        // 状态 / 日期都在数据库里、分页之前过滤, 并按 DataScope 收窄 (getSalesOrdersByStatus 不收窄)
        return salesService.getSalesOrdersFiltered(factoryId, status, from, to, page, size);
    }

    /** 最近一次有订单的订单日期: 取最近创建的 100 单里最大的订单日期 (已按数据范围收窄); 没有订单为 null。 */
    private LocalDate latestOrderDate(String factoryId) {
        LocalDate latest = null;
        PageResponse<SalesOrder> recent = salesService.getSalesOrders(factoryId, 1, 100);
        for (SalesOrder order : recent == null ? List.<SalesOrder>of() : recent.getContent()) {
            if (order.getOrderDate() != null && (latest == null || order.getOrderDate().isAfter(latest))) {
                latest = order.getOrderDate();
            }
        }
        return latest;
    }

    /**
     * 订单摘要: 只取标量字段, 不碰 LAZY 的 items / suppliedMaterials。流式入口 (intent-sse 线程) 没有 Hibernate Session,
     * 以前把 JPA 实体直接放进 data, 序列化到 items 时 LazyInitializationException = 用户看到「结果处理失败」。
     * 回复文字 (ResultFormatterServiceImpl.formatOrderListBody) 只用 orderNumber / customerName / totalAmount / status。
     */
    /** 单张订单的标量摘要 (不碰 LAZY 集合); OrderQueryTool / OrderTodayTool 共用。 */
    static Map<String, Object> toSummary(SalesOrder order) {
        Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", order.getId());
            row.put("orderNumber", order.getOrderNumber());
            row.put("customerId", order.getCustomerId());
            row.put("customerName", order.getCustomerName());
            row.put("orderDate", order.getOrderDate());
            row.put("requiredDeliveryDate", order.getRequiredDeliveryDate());
            row.put("status", order.getStatus() == null ? null : order.getStatus().name());
            row.put("totalAmount", order.getTotalAmount());
            row.put("discountAmount", order.getDiscountAmount());
            row.put("taxAmount", order.getTaxAmount());
            row.put("salesperson", order.getSalesperson());
            row.put("remark", order.getRemark());
        return row;
    }

    static List<Map<String, Object>> toSummaries(List<SalesOrder> orders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (orders == null) {
            return rows;
        }
        for (SalesOrder order : orders) {
            rows.add(toSummary(order));
        }
        return rows;
    }

    static boolean wantsCustomerSummary(Map<String, Object> params) {
        if ("customer".equals(params.get("groupBy"))) {
            return true;
        }
        Object said = params.get("originalUserInput") != null ? params.get("originalUserInput") : params.get("userInput");
        if (!(said instanceof String text)) {
            return false;
        }
        return text.contains("按客户") && (text.contains("汇总") || text.contains("统计") || text.contains("分组")
                || text.contains("排名") || text.contains("合计"))
                || text.contains("各客户") || text.contains("每个客户") || text.contains("哪个客户下单")
                || text.contains("哪个客户订单") || text.contains("客户下单最多");
    }

    static List<Map<String, Object>> summarizeByCustomer(PageResponse<SalesOrder> orders) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (SalesOrder order : orders.getContent()) {
            String name = order.getCustomerName() != null && !order.getCustomerName().isBlank()
                    ? order.getCustomerName() : "未填写客户";
            Map<String, Object> row = byName.computeIfAbsent(name, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("customerName", k);
                r.put("orderCount", 0);
                r.put("totalAmount", BigDecimal.ZERO);
                return r;
            });
            row.put("orderCount", (Integer) row.get("orderCount") + 1);
            BigDecimal amount = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
            row.put("totalAmount", ((BigDecimal) row.get("totalAmount")).add(amount));
        }
        List<Map<String, Object>> rows = new ArrayList<>(byName.values());
        rows.sort((a, b) -> {
            int c = Integer.compare((Integer) b.get("orderCount"), (Integer) a.get("orderCount"));
            return c != 0 ? c : ((BigDecimal) b.get("totalAmount")).compareTo((BigDecimal) a.get("totalAmount"));
        });
        return rows;
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
