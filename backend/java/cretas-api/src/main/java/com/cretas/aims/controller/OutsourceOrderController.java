package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.outsource.OutsourceDtos.*;
import com.cretas.aims.service.outsource.OutsourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 委外单 (原生委外闭环)。设计见 {@code docs/specs/outsource-closed-loop-design.md}。
 * 权限沿用生产模块 (D11): 读 production:read_write / production:read, 写 production:read_write。
 * 数据按路径 factoryId 隔离; 跨厂的单一律 404。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/outsource-orders")
@RequiredArgsConstructor
@RequireModule("production_plan")
@Tag(name = "委外单", description = "委外单 + 任务行 + 发料/退料/收货/退货/损耗/结单")
public class OutsourceOrderController {


    private final OutsourceService service;

    @GetMapping
    @RequirePermission({"production:read_write", "production:read"})
    @Operation(summary = "委外单列表 (服务端按委外厂 / 状态 / 单号筛选)")
    public ApiResponse<Map<String, Object>> list(@PathVariable String factoryId,
                                                 @RequestParam(required = false) String supplierId,
                                                 @RequestParam(required = false) List<String> status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Page<OrderSummary> p = service.list(factoryId, supplierId, status, keyword, page, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", p.getContent());
        data.put("totalElements", p.getTotalElements());
        data.put("totalPages", p.getTotalPages());
        data.put("page", page);
        data.put("size", size);
        return ApiResponse.success(data);
    }

    @GetMapping("/{orderId}")
    @RequirePermission({"production:read_write", "production:read"})
    public ApiResponse<OrderDetail> detail(@PathVariable String factoryId, @PathVariable String orderId) {
        return ApiResponse.success(service.detail(factoryId, orderId));
    }

    @GetMapping("/process-links")
    @RequirePermission({"production:read_write", "production:read"})
    @Operation(summary = "工单下的委外工序 (工单详情用)")
    public ApiResponse<List<ProcessLink>> processLinks(@PathVariable String factoryId,
                                                       @RequestParam String productionPlanId) {
        return ApiResponse.success(service.processLinks(factoryId, productionPlanId));
    }

    @GetMapping("/{orderId}/lines/{lineId}/receive-suggestion")
    @RequirePermission({"production:read_write", "production:read"})
    public ApiResponse<ReceiveSuggestion> receiveSuggestion(@PathVariable String factoryId,
                                                            @PathVariable String orderId,
                                                            @PathVariable String lineId,
                                                            @RequestParam(required = false) BigDecimal goodQuantity,
                                                            @RequestParam(required = false) BigDecimal defectQuantity) {
        return ApiResponse.success(service.suggestReceive(factoryId, orderId, lineId, goodQuantity, defectQuantity));
    }

    @PostMapping
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> create(@PathVariable String factoryId, @RequestBody CreateOrderRequest body,
                                           @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("委外单已创建", service.create(factoryId, body, userId));
    }

    @PostMapping("/{orderId}/release")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> release(@PathVariable String factoryId, @PathVariable String orderId,
                                            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("委外单已下达", service.release(factoryId, orderId, userId));
    }

    @PostMapping("/{orderId}/cancel")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> cancel(@PathVariable String factoryId, @PathVariable String orderId,
                                           @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("委外单已取消", service.cancel(factoryId, orderId, userId));
    }

    @PostMapping("/{orderId}/close")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> close(@PathVariable String factoryId, @PathVariable String orderId,
                                          @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("委外单已结单", service.close(factoryId, orderId, userId));
    }

    @PostMapping("/{orderId}/lines/{lineId}/issue")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> issue(@PathVariable String factoryId, @PathVariable String orderId,
                                          @PathVariable String lineId, @RequestBody IssueRequest body,
                                          @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("已发料", service.issue(factoryId, orderId, lineId, body, userId));
    }

    @PostMapping("/{orderId}/lines/{lineId}/material-return")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> materialReturn(@PathVariable String factoryId, @PathVariable String orderId,
                                                   @PathVariable String lineId, @RequestBody MaterialReturnRequest body,
                                                   @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("已退料", service.returnMaterial(factoryId, orderId, lineId, body, userId));
    }

    @PostMapping("/{orderId}/lines/{lineId}/receive")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> receive(@PathVariable String factoryId, @PathVariable String orderId,
                                            @PathVariable String lineId, @RequestBody ReceiveRequest body,
                                            @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("已收货", service.receive(factoryId, orderId, lineId, body, userId));
    }

    @PostMapping("/{orderId}/lines/{lineId}/goods-return")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> goodsReturn(@PathVariable String factoryId, @PathVariable String orderId,
                                                @PathVariable String lineId, @RequestBody GoodsReturnRequest body,
                                                @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("已退货返工", service.returnGoods(factoryId, orderId, lineId, body, userId, null));
    }

    @PostMapping("/{orderId}/lines/{lineId}/loss")
    @RequirePermission({"production:read_write"})
    public ApiResponse<OrderDetail> loss(@PathVariable String factoryId, @PathVariable String orderId,
                                         @PathVariable String lineId, @RequestBody LossRequest body,
                                         @RequestAttribute("userId") @Parameter(hidden = true) Long userId) {
        return ApiResponse.success("已登记损耗", service.registerLoss(factoryId, orderId, lineId, body, userId));
    }
}
