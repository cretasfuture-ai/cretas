package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.listsummary.ListSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 2 Track I — U-FOOTER-1 列表底部 sticky footer 实时合计.
 *
 * Path: POST /api/mobile/{factoryId}/list-summary/{entityType}
 *
 * Supported entityType values (Day 2 + Day 4-prep extension):
 *   - salesOrder        — 销售订单 (sales_orders)
 *   - purchaseOrder     — 采购订单 (purchase_orders)
 *   - inventory         — 物料库存 (material_batches)
 *   - wastage           — 餐饮损耗 (wastage_records)
 *   - attendance        — 考勤打卡 (time_clock_records)
 *   - returnOrder       — 退货单 (return_orders)
 *   - internalTransfer  — 内部调拨 (internal_transfers; source 或 target = factoryId)
 *   - qualityInspection — 质检 (quality_inspections; result PASS/FAIL/PENDING)
 *   - productionPlan    — 生产计划 (production_plans; 计划/完成数量 + 完成率)
 *   - shipment          — 发货 (sales_delivery_records; 待发货/已完成 split)
 *
 * Frontend: RN StickyFooterSummary + Vue TableFooter.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/list-summary")
@RequiredArgsConstructor
@Tag(name = "列表合计", description = "Sticky Footer 实时合计 (U-FOOTER-1)")
public class ListSummaryController {

    private final ListSummaryService listSummaryService;
    private final PermissionService permissionService;
    private final UserRepository userRepository;

    /**
     * Entity-specific read gates. The sets mirror the permissions on the backing
     * GET/list controllers, while keeping the summary endpoint's business gate
     * narrower than its former cross-module OR union.
     */
    private static final Map<String, List<String>> ENTITY_READ_PERMISSIONS = Map.ofEntries(
            Map.entry("salesOrder", List.of("sales:read", "sales:read_write", "finance:read", "procurement:read")),
            Map.entry("purchaseOrder", List.of("procurement:read", "procurement:read_write",
                    "sales:read", "warehouse:read", "warehouse:read_write", "finance:read")),
            Map.entry("inventory", List.of("warehouse:read", "warehouse:read_write",
                    "inventory:read", "inventory:read_write")),
            // WastageRecord read routes are currently unannotated; its write and
            // accounting routes are inventory/price/finance, so inventory read is
            // the least-privilege domain read for this non-price summary.
            Map.entry("wastage", List.of("restaurant:read", "restaurant:read_write",
                    "inventory:read", "inventory:read_write")),
            Map.entry("attendance", List.of("hr:read", "hr:read_write")),
            Map.entry("returnOrder", List.of("sales:read", "sales:read_write",
                    "procurement:read", "procurement:read_write", "finance:read", "finance:read_write")),
            Map.entry("internalTransfer", List.of("inventory:read", "inventory:write")),
            // Processing quality read routes are unannotated; quality:read_write
            // protects the corresponding write route, so quality read is the
            // bounded domain gate for this summary.
            Map.entry("qualityInspection", List.of("quality:read", "quality:read_write")),
            Map.entry("productionPlan", List.of("production:read", "production:read_write",
                    "scheduling:read", "scheduling:read_write", "warehouse:read", "warehouse:read_write")),
            Map.entry("shipment", List.of("sales:read", "sales:read_write",
                    "warehouse:read", "warehouse:read_write")));

    @PostMapping("/{entityType}")
    @Operation(summary = "查询列表合计统计",
               description = "按 filterConditions 过滤后, 计算 count/sum/avg 等聚合 stat, 给底部 sticky footer 渲染")
    public ApiResponse<ListSummaryResponse> getSummary(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String entityType,
            @RequestBody(required = false) ListSummaryRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (request == null) {
            request = new ListSummaryRequest();
        }

        User currentUser = resolveCurrentUser(httpRequest);
        if (currentUser == null) {
            return reject(httpResponse, 401, "用户未登录");
        }

        List<String> requiredPermissions = ENTITY_READ_PERMISSIONS.get(entityType);
        if (requiredPermissions == null) {
            return reject(httpResponse, 403, "不支持的列表合计实体类型");
        }

        boolean allowed;
        try {
            allowed = permissionService.hasAnyPermission(currentUser,
                    requiredPermissions.toArray(String[]::new));
        } catch (RuntimeException ex) {
            log.warn("list-summary permission check failed for userId={}, entity={}: {}",
                    currentUser.getId(), entityType, ex.getMessage());
            allowed = false;
        }
        if (!allowed) {
            return reject(httpResponse, 403, "当前角色无权读取该列表合计");
        }

        // S5-12: 采购单合计金额按配置的采购读权限判; 退货合计不分采购 / 销售退货 (见
        // computeReturnOrderSummary 只按状态过滤), 所以要两种口径都能看才给金额。
        String priceSpec = summaryPriceSpec(entityType, currentUser);
        if (priceSpec != null) {
            httpRequest.setAttribute(com.cretas.aims.security.PriceScope.REQUEST_ATTRIBUTE, priceSpec);
        }

        log.debug("list-summary: factory={}, entity={}, filter={}",
                factoryId, entityType, request.getFilterConditions());
        ListSummaryResponse response = listSummaryService.computeSummary(factoryId, entityType, request);
        return ApiResponse.success("查询成功", response);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.security.PriceVisibility priceVisibility;

    /** 本次合计里金额统计的价格口径; null = 沿用默认口径。 */
    private String summaryPriceSpec(String entityType, User user) {
        if ("purchaseOrder".equals(entityType)) {
            return com.cretas.aims.security.PriceSensitive.PURCHASE;
        }
        if ("returnOrder".equals(entityType)) {
            com.cretas.aims.security.PriceVisibility pv = priceVisibility != null ? priceVisibility
                    : new com.cretas.aims.security.PriceVisibility(permissionService);
            // 两个口径都要: 默认口径能看就再按采购口径判, 默认口径都看不到就留默认 (照样剥)
            return pv.canView(user, com.cretas.aims.security.PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)
                    ? com.cretas.aims.security.PriceSensitive.PURCHASE : null;
        }
        return null;
    }

    private User resolveCurrentUser(HttpServletRequest request) {
        Object rawUserId = request.getAttribute("userId");
        Long userId;
        if (rawUserId instanceof Long value) {
            userId = value;
        } else if (rawUserId instanceof Integer value) {
            userId = value.longValue();
        } else if (rawUserId instanceof String value) {
            try {
                userId = Long.valueOf(value);
            } catch (NumberFormatException ex) {
                return null;
            }
        } else {
            return null;
        }
        try {
            Optional<User> user = userRepository.findById(userId);
            return user.orElse(null);
        } catch (RuntimeException ex) {
            log.warn("list-summary user lookup failed for userId={}: {}", userId, ex.getMessage());
            return null;
        }
    }

    private <T> ApiResponse<T> reject(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        return ApiResponse.error(status, message);
    }
}
