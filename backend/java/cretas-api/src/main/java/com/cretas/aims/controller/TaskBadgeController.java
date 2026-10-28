package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.TaskBadgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.LongSupplier;

/**
 * Sidebar task badge counts (S6-09). Permission annotations mirror the list endpoints each badge used to
 * read, so a caller who could not open the list cannot read its count. When a count cannot be computed
 * the answer is {@code state=UNKNOWN} with no number: never 0, never a partial page's size.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/task-badges")
@RequiredArgsConstructor
@Tag(name = "任务角标")
public class TaskBadgeController {

    public static final String KNOWN = "KNOWN";
    public static final String UNKNOWN = "UNKNOWN";

    private final TaskBadgeService service;

    public record BadgeCount(String state, Long count, String reason) {
        static BadgeCount known(long count) { return new BadgeCount(KNOWN, count, null); }
        static BadgeCount unknown(String reason) { return new BadgeCount(UNKNOWN, null, reason); }
    }

    @GetMapping("/sales-orders")
    @RequirePermission({"sales:read_write", "sales:read", "finance:read", "procurement:read"})
    @Operation(summary = "待处理销售订单数（按调用者的数据范围）")
    public ApiResponse<BadgeCount> salesOrders(@PathVariable String factoryId) {
        return ApiResponse.success(count(factoryId, () -> service.countActionableSalesOrders(factoryId)));
    }

    @GetMapping("/unordered-inbound")
    @RequirePermission({"operations:read", "operations:read_write", "warehouse:read", "warehouse:read_write"})
    @Operation(summary = "待审批的无订单入库申请数")
    public ApiResponse<BadgeCount> unorderedInbound(@PathVariable String factoryId) {
        return ApiResponse.success(count(factoryId, () -> service.countPendingUnorderedInbound(factoryId)));
    }

    private BadgeCount count(String factoryId, LongSupplier supplier) {
        try {
            return BadgeCount.known(supplier.getAsLong());
        } catch (RuntimeException e) {
            log.warn("Task badge count unavailable factory={} type={}", factoryId, e.getClass().getSimpleName());
            return BadgeCount.unknown("计数暂时无法计算");
        }
    }
}
