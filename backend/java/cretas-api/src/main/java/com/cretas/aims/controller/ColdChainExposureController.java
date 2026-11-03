package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.AffectedReport;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.AlertView;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.AssignCommand;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.EquipmentView;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.HoldCommand;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.HoldOutcome;
import com.cretas.aims.service.foodsafety.coldchain.ColdChainBatchExposureService.LocationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 温度关联批次 (食品 DAG 缺口 15): 冷链告警 → 受影响批次 → 转质检; 冷链设备 ↔ 库位。
 *
 * <p>页面: web-admin「质量管理 → 冷链超温批次」({@code /quality/cold-chain})。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/cold-chain")
@RequiredArgsConstructor
@Tag(name = "冷链超温批次", description = "超温时段 × 批次在库时段 → 受影响批次, 一键转质检")
public class ColdChainExposureController {

    private final ColdChainBatchExposureService service;

    @Operation(summary = "冷链告警列表 (最近 N 天)")
    @RequirePermission({"quality:read_write", "quality:read", "warehouse:read", "warehouse:read_write"})
    @GetMapping("/alerts")
    public ApiResponse<List<AlertView>> listAlerts(@PathVariable String factoryId,
                                                   @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(service.listAlerts(factoryId, days));
    }

    @Operation(summary = "某条告警的超温时段与受影响批次 (原料 / 在制半成品 / 成品)")
    @RequirePermission({"quality:read_write", "quality:read", "warehouse:read", "warehouse:read_write"})
    @GetMapping("/alerts/{alertId}/affected-batches")
    public ApiResponse<AffectedReport> affected(@PathVariable String factoryId, @PathVariable Long alertId) {
        return ApiResponse.success(service.affectedBatches(factoryId, alertId));
    }

    @Operation(summary = "把选中的受影响批次转质检 (沿用质检放行的待检状态)")
    @RequirePermission({"quality:read_write"})
    @PostMapping("/alerts/{alertId}/holds")
    public ApiResponse<List<HoldOutcome>> hold(@PathVariable String factoryId, @PathVariable Long alertId,
                                               @RequestBody HoldCommand body,
                                               @RequestAttribute("userId") Long userId,
                                               @RequestAttribute(value = "username", required = false) String username) {
        List<HoldOutcome> out = service.holdBatches(factoryId, alertId, body, userId, username);
        long held = out.stream().filter(o -> "HELD".equals(o.outcome())).count();
        return ApiResponse.success("已转质检 " + held + " 个批次", out);
    }

    @Operation(summary = "冷链设备 (含当前所在仓库)")
    @RequirePermission({"quality:read_write", "quality:read", "warehouse:read", "warehouse:read_write"})
    @GetMapping("/equipment")
    public ApiResponse<List<EquipmentView>> equipment(@PathVariable String factoryId) {
        return ApiResponse.success(service.listEquipment(factoryId));
    }

    @Operation(summary = "某台设备的库位历史")
    @RequirePermission({"quality:read_write", "quality:read", "warehouse:read", "warehouse:read_write"})
    @GetMapping("/equipment/{equipmentId}/locations")
    public ApiResponse<List<LocationView>> locations(@PathVariable String factoryId, @PathVariable Long equipmentId) {
        return ApiResponse.success(service.listLocations(factoryId, equipmentId));
    }

    @Operation(summary = "登记设备从某时刻起放在某个仓库 (关掉当前时段, 不改写历史)")
    @RequirePermission({"quality:read_write", "warehouse:read_write"})
    @PostMapping("/equipment/{equipmentId}/locations")
    public ApiResponse<LocationView> assign(@PathVariable String factoryId, @PathVariable Long equipmentId,
                                            @RequestBody AssignCommand body,
                                            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success("已登记库位", service.assignLocation(factoryId, equipmentId, body, userId));
    }
}
