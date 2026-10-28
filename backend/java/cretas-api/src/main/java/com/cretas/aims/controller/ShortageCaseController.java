package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.shortage.ShortageCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 缺料异常处理实例 (S4 L1): 列表 / 详情 / 手动刷新。只读本厂的实例。
 *
 * <p>「缺口已解决」和「订单交付」分开给: gapResolvedAt 来自物料事实, salesOrderStatus 是销售单自己的状态。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/shortage-cases")
@Tag(name = "缺料异常", description = "一个缺料事件一条处理实例: 缺口依据、阶段、该谁处理、留痕")
public class ShortageCaseController {

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseLineRepository lineRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final ShortageCaseService caseService;

    public record CaseView(String id, String sourceType, String sourceId, String salesOrderNumber,
                           String salesOrderStatus, String stage, String ownerRole, Long ownerUserId,
                           int gapLineCount, LocalDateTime gapResolvedAt, LocalDateTime lastRefreshedAt,
                           String lastRefreshReason, LocalDateTime closedAt, LocalDateTime createdAt) {
    }

    public record LineView(String materialTypeId, String materialName, String unit, BigDecimal requiredQty,
                           BigDecimal releasedQty, BigDecimal inspectingQty, BigDecimal onOrderQty,
                           int onOrderUnitMismatchLines, BigDecimal gapQty) {
        static LineView of(ShortageCaseLine l) {
            return new LineView(l.getMaterialTypeId(), l.getMaterialName(), l.getUnit(), l.getRequiredQty(),
                    l.getReleasedQty(), l.getInspectingQty(), l.getOnOrderQty(), l.getOnOrderUnitMismatchLines(),
                    l.getGapQty());
        }
    }

    public record EventView(String eventType, String fromStage, String toStage, String triggerReason, String detail,
                            String actorType, Long actorId, LocalDateTime createdAt) {
        static EventView of(ShortageCaseEvent e) {
            return new EventView(e.getEventType().name(), e.getFromStage() == null ? null : e.getFromStage().name(),
                    e.getToStage() == null ? null : e.getToStage().name(), e.getTriggerReason(), e.getDetail(),
                    e.getActorType(), e.getActorId(), e.getCreatedAt());
        }
    }

    @RequirePermission({"procurement:read", "procurement:read_write", "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write", "quality:read", "quality:read_write",
            "sales:read", "sales:read_write"})
    @GetMapping
    @Operation(summary = "本厂缺料实例列表", description = "默认只看未关闭的, 按最近刷新排序")
    public ApiResponse<List<CaseView>> list(@PathVariable String factoryId,
                                            @RequestParam(defaultValue = "true") boolean openOnly,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        var rows = caseRepository.findForFactory(factoryId, openOnly, PageRequest.of(Math.max(page, 0), safeSize));
        return ApiResponse.success("获取成功", rows.getContent().stream().map(this::view).toList());
    }

    @RequirePermission({"procurement:read", "procurement:read_write", "production:read", "production:read_write",
            "warehouse:read", "warehouse:read_write", "quality:read", "quality:read_write",
            "sales:read", "sales:read_write"})
    @GetMapping("/{caseId}")
    @Operation(summary = "缺料实例详情", description = "缺口行 (需求 / 已放行 / 质检中 / 在途 / 缺口) 与留痕")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String factoryId, @PathVariable String caseId) {
        ShortageCase c = own(factoryId, caseId);
        return ApiResponse.success("获取成功", Map.of(
                "case", view(c),
                "lines", lineRepository.findByCaseIdOrderByIdAsc(c.getId()).stream().map(LineView::of).toList(),
                "events", eventRepository.findByCaseIdOrderByIdAsc(c.getId()).stream().map(EventView::of).toList()));
    }

    @RequirePermission({"procurement:read_write", "production:read_write", "warehouse:read_write", "quality:read_write"})
    @PostMapping("/{caseId}/refresh")
    @Operation(summary = "按当前事实重新核对缺口", description = "输入没变就什么都不写")
    public ApiResponse<CaseView> refresh(@PathVariable String factoryId, @PathVariable String caseId) {
        ShortageCase c = own(factoryId, caseId);
        if (c.getSourceType() != ShortageCase.SourceType.SALES_ORDER) {
            throw new BusinessException(400, "不支持的来源: " + c.getSourceType());
        }
        if (c.getStage().isTerminal()) {
            throw new BusinessException(409, "实例已结束 (" + c.getStage() + "), 不再刷新");
        }
        caseService.refreshForSalesOrder(factoryId, c.getSourceId(), "MANUAL_REFRESH");
        return ApiResponse.success("已核对", view(own(factoryId, caseId)));
    }

    private ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "缺料实例不存在或不属于本厂"));
    }

    private CaseView view(ShortageCase c) {
        var order = c.getSourceType() == ShortageCase.SourceType.SALES_ORDER
                ? salesOrderRepository.findById(c.getSourceId()).filter(o -> c.getFactoryId().equals(o.getFactoryId())).orElse(null)
                : null;
        return new CaseView(c.getId(), c.getSourceType().name(), c.getSourceId(),
                order == null ? null : order.getOrderNumber(),
                order == null || order.getStatus() == null ? null : order.getStatus().name(),
                c.getStage().name(), c.getOwnerRole(), c.getOwnerUserId(), c.getGapLineCount(), c.getGapResolvedAt(),
                c.getLastRefreshedAt(), c.getLastRefreshReason(), c.getClosedAt(), c.getCreatedAt());
    }
}
