package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.supplyimpact.SupplyImpactDTO;
import com.cretas.aims.service.supplyimpact.SupplyImpactService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 供应商 / 原料品种出发的影响分析 (断供 / 质量 / 涨价), 全部只读。
 * 闸与成本报表一致 (含价格口径的数据): finance 或采购价查看权限。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/supply-impact")
@RequirePermission({"finance:read", "finance:read_write", "procurement:price:view"})
@RequiredArgsConstructor
public class SupplyImpactController {

    private final SupplyImpactService supplyImpactService;

    @GetMapping("/disruption")
    public ResponseEntity<ApiResponse<SupplyImpactDTO.DisruptionView>> disruption(
            @PathVariable String factoryId,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String materialTypeId,
            @RequestParam(required = false) Integer horizonDays) {
        return ResponseEntity.ok(ApiResponse.success(
                supplyImpactService.disruption(factoryId, supplierId, materialTypeId, horizonDays)));
    }

    @GetMapping("/quality")
    public ResponseEntity<ApiResponse<SupplyImpactDTO.QualityView>> quality(
            @PathVariable String factoryId,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String materialTypeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.success(
                supplyImpactService.quality(factoryId, supplierId, materialTypeId, from, to)));
    }

    @GetMapping("/price")
    public ResponseEntity<ApiResponse<SupplyImpactDTO.PriceView>> price(
            @PathVariable String factoryId,
            @RequestParam String materialTypeId,
            @RequestParam BigDecimal percent) {
        return ResponseEntity.ok(ApiResponse.success(supplyImpactService.price(factoryId, materialTypeId, percent)));
    }
}
