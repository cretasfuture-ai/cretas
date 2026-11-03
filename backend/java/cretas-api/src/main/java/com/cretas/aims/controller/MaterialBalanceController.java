package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import com.cretas.aims.service.yield.balance.MaterialBalanceThresholdProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 物料平衡表 (缺口 #07): 在工序级投入产出对比之上, 给出「投入 = 产出 + 副产 + 已确认损耗 + 未解释差额」。 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/yield/material-balance")
@Tag(name = "物料平衡", description = "按生产计划 / 工序的物料平衡表与阈值")
@RequiredArgsConstructor
@RequireModule("production")
public class MaterialBalanceController {

    private final MaterialBalanceService service;
    private final MaterialBalanceThresholdProvider thresholds;

    @GetMapping
    @Operation(summary = "物料平衡表 (按报工日期区间 / 计划筛选)")
    public ApiResponse<MaterialBalanceTableDTO> table(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String planId) {
        return ApiResponse.success(service.table(factoryId, startDate, endDate, planId));
    }

    @GetMapping("/thresholds")
    @Operation(summary = "本厂物料平衡黄/红阈值 (百分数)")
    public ApiResponse<Map<String, BigDecimal>> getThresholds(@PathVariable String factoryId) {
        return ApiResponse.success(view(thresholds.forFactory(factoryId)));
    }

    @PutMapping("/thresholds")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Operation(summary = "设置本厂物料平衡黄/红阈值 (百分数, 0 < 黄 < 红 <= 100)")
    public ApiResponse<Map<String, BigDecimal>> putThresholds(
            @PathVariable String factoryId, @RequestBody Map<String, BigDecimal> body) {
        return ApiResponse.success(view(thresholds.save(factoryId, body.get("warnPct"), body.get("redPct"))));
    }

    private static Map<String, BigDecimal> view(MaterialBalanceCalculator.Thresholds t) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("warnPct", t.warn().multiply(new BigDecimal("100")).stripTrailingZeros());
        m.put("redPct", t.red().multiply(new BigDecimal("100")).stripTrailingZeros());
        return m;
    }
}
