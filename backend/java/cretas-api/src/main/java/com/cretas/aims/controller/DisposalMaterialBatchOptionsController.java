package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MaterialBatchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** Quality-owned selector; it deliberately does not expose the inventory DTO or grant warehouse access. */
@RestController
@RequiredArgsConstructor
@Validated
@RequireModule("quality_inspection")
@RequestMapping("/api/mobile/{factoryId}/disposal-records/eligible-material-batches")
public class DisposalMaterialBatchOptionsController {
    private final MaterialBatchService materialBatchService;

    @GetMapping
    @RequirePermission({"quality:read", "quality:read_write", "warehouse:read_write"})
    public ApiResponse<PageResponse<MaterialBatchOption>> list(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        PageRequest request = PageRequest.of(page, size, "createdAt", "DESC");
        request.setStatus(MaterialBatchStatus.AVAILABLE.name());
        request.setKeyword(keyword);
        // Reuse the existing proxied, DataScope-aware list method, including its SQL-side status filter.
        PageResponse<MaterialBatchDTO> result = materialBatchService.getMaterialBatchList(factoryId, request);
        var options = result.getContent().stream().map(batch -> project(factoryId, batch)).toList();
        return ApiResponse.success(PageResponse.of(options, result.getPage(), result.getSize(), result.getTotalElements()));
    }

    private static MaterialBatchOption project(String factoryId, MaterialBatchDTO batch) {
        if (!factoryId.equals(batch.getFactoryId()) || batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
            throw new BusinessException(403, "原料候选批次范围不一致，请刷新后重试");
        }
        return new MaterialBatchOption(batch.getId(), batch.getBatchNumber(), batch.getMaterialTypeId(),
                batch.getMaterialName(), batch.getCurrentQuantity(), batch.getQuantityUnit());
    }

    public record MaterialBatchOption(String id, String batchNumber, String materialTypeId,
                                      String materialName, BigDecimal currentQuantity, String quantityUnit) {}
}
