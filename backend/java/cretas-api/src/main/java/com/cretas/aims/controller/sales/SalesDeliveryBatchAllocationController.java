package com.cretas.aims.controller.sales;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.sales.BatchAllocationDTO;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.service.sales.SalesDeliveryBatchAllocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * PC 端销售发货批次分配 REST 接口（P0-13 昆山六扇门）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "销售发货批次分配", description = "P0-13 强制批次追溯")
@RequestMapping("/api/mobile/{factoryId}/sales-deliveries/items/{deliveryItemId}/batch-allocations")
public class SalesDeliveryBatchAllocationController {

    private final SalesDeliveryBatchAllocationService service;

    @GetMapping
    @Operation(summary = "查询某发货行的批次分配")
    public ApiResponse<List<SalesDeliveryItemBatchAllocation>> list(
            @PathVariable String factoryId,
            @PathVariable String deliveryItemId) {
        return ApiResponse.success("查询成功", service.listByDeliveryItem(factoryId, deliveryItemId));
    }

    /*
     * 🔴 2026-08-20: 权限从「仅 sales:read_write」放宽到「或 warehouse:read_write」。
     *
     * 起因是真机走查: 仓管在手机上做发货确认, 被「还有 N 行未分配批次」挡住,
     * 而界面告诉他「分配批次要到网页端做」—— 他站在货堆边, 手上只有手机。
     *
     * ▎**分配批次的本质是「从我仓库里挑哪几个批次发出去」, 这是仓管的活。**
     * 销售员不在仓库, 也没有能力决定用哪个批次(效期/实际货位都在仓管手上)。
     * 原来只认 sales:read_write, 等于把一个仓库动作锁在销售角色后面。
     *
     * ⚠️ 与 M-13 是**同一个毛病的镜像**: 那次是「审采购单为什么要仓储权限」,
     * 这次是「仓管发货为什么要销售写权限」。
     *
     * ⛔ 没有给 warehouse_manager 加 sales=rw —— 那会顺带放开改价、改销售单等一堆
     * 与仓储无关的写操作。只在**批次分配这两个端点**上承认仓储角色, 是最小放宽。
     * (requireAll 默认 false = 任一满足即可)
     *
     * 硬约束 8 登记: 本文件共 2 处 sales:read_write(POST 分配 / DELETE 清空),
     * **两处都改了** —— 仓管重新分配时要先清空, 只放宽一处会卡在第二步。
     */
    @RequirePermission({"sales:read_write", "warehouse:read_write"})
    @RequireModule("sales_order")
    @PostMapping
    @Operation(summary = "设置发货行的批次分配（先清空再写入）")
    public ApiResponse<Map<String, Object>> allocate(
            @PathVariable String factoryId,
            @PathVariable String deliveryItemId,
            @RequestBody AllocateRequest request) {
        service.allocateBatches(factoryId, deliveryItemId, request.getAllocations());
        return ApiResponse.success("批次分配成功", Map.of(
                "deliveryItemId", deliveryItemId,
                "fullyAllocated", service.isFullyAllocated(factoryId, deliveryItemId)
        ));
    }

    @GetMapping("/recommend-fifo")
    @Operation(summary = "FIFO 成品批次推荐（按生产日期升序）",
            description = "T4-D5 (#572): sourceWarehouseCode 可选 — 指定则从对应仓库 FIFO 推荐, 缺省 = WH-LOG. "
                    + "🔴 C1 (2026-07-05): unit 可选 — 发货行单位(如 SalesDeliveryItem.unit), 用于把跨单位的成品批次"
                    + "(如同产品一批 kg 一批盒)统一换算为该单位比较/推荐; 缺省回落产品默认单位.")
    public ApiResponse<List<Map<String, Object>>> recommendFifo(
            @PathVariable String factoryId,
            @PathVariable String deliveryItemId,
            @RequestParam String productTypeId,
            @RequestParam BigDecimal requiredQty,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false) String sourceWarehouseCode) {
        return ApiResponse.success("FIFO 推荐 (按生产日期升序)",
                service.recommendFifo(
                        factoryId, deliveryItemId, productTypeId, requiredQty, unit, sourceWarehouseCode));
    }

    @GetMapping("/stock-warehouses")
    @Operation(summary = "该产品有可用成品库存的仓库清单",
            description = "🔴 G1 (2026-07-03): 诚实空态用 — 发货行来源仓无货时提示成品实际所在仓库, 供改选来源仓/调拨。")
    public ApiResponse<List<String>> stockWarehouses(
            @PathVariable String factoryId,
            @RequestParam String productTypeId) {
        return ApiResponse.success("查询成功",
                service.warehousesWithAvailableStock(factoryId, productTypeId));
    }

    /*
     * 🔴 2026-08-20: 权限从「仅 sales:read_write」放宽到「或 warehouse:read_write」。
     *
     * 起因是真机走查: 仓管在手机上做发货确认, 被「还有 N 行未分配批次」挡住,
     * 而界面告诉他「分配批次要到网页端做」—— 他站在货堆边, 手上只有手机。
     *
     * ▎**分配批次的本质是「从我仓库里挑哪几个批次发出去」, 这是仓管的活。**
     * 销售员不在仓库, 也没有能力决定用哪个批次(效期/实际货位都在仓管手上)。
     * 原来只认 sales:read_write, 等于把一个仓库动作锁在销售角色后面。
     *
     * ⚠️ 与 M-13 是**同一个毛病的镜像**: 那次是「审采购单为什么要仓储权限」,
     * 这次是「仓管发货为什么要销售写权限」。
     *
     * ⛔ 没有给 warehouse_manager 加 sales=rw —— 那会顺带放开改价、改销售单等一堆
     * 与仓储无关的写操作。只在**批次分配这两个端点**上承认仓储角色, 是最小放宽。
     * (requireAll 默认 false = 任一满足即可)
     *
     * 硬约束 8 登记: 本文件共 2 处 sales:read_write(POST 分配 / DELETE 清空),
     * **两处都改了** —— 仓管重新分配时要先清空, 只放宽一处会卡在第二步。
     */
    @RequirePermission({"sales:read_write", "warehouse:read_write"})
    @RequireModule("sales_order")
    @DeleteMapping
    @Operation(summary = "清空发货行的批次分配")
    public ApiResponse<Void> clear(
            @PathVariable String factoryId,
            @PathVariable String deliveryItemId) {
        service.clearAllocations(factoryId, deliveryItemId);
        return ApiResponse.success("清空成功", null);
    }

    @lombok.Data
    public static class AllocateRequest {
        private List<BatchAllocationDTO> allocations;
    }
}
