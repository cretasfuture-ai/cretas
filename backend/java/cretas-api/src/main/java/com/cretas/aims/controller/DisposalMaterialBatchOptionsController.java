package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.DisposalMaterialBatchOptionsService;
import com.cretas.aims.service.DisposalMaterialBatchOptionsService.MaterialBatchOption;
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

/** Quality-owned selector; it deliberately does not expose the inventory DTO or grant warehouse access. */
@RestController
@RequiredArgsConstructor
@Validated
@RequireModule("quality_inspection")
@RequestMapping("/api/mobile/{factoryId}/disposal-records/eligible-material-batches")
public class DisposalMaterialBatchOptionsController {
    private final DisposalMaterialBatchOptionsService options;

    @GetMapping
    @RequirePermission({"quality:read", "quality:read_write", "warehouse:read_write"})
    public ApiResponse<PageResponse<MaterialBatchOption>> list(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        return ApiResponse.success(options.list(factoryId, page, size, keyword));
    }
}
