package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.finance.ArPostingPending;
import com.cretas.aims.entity.finance.ArPostingPendingStatus;
import com.cretas.aims.service.finance.ArPostingPendingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/** 发货应收挂账失败的待补账记录 —— 只读。补账动作本期未做。 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/ar-posting-pending")
@RequiredArgsConstructor
@Tag(name = "待补账记录", description = "发货成功但应收自动挂账失败的记录(只读)")
@RequirePermission({"finance:read_write", "finance:read"})
public class ArPostingPendingController {

    private final ArPostingPendingService service;

    @GetMapping
    @Operation(summary = "待补账记录列表(可按状态筛选)")
    @RequireModule("finance_ar")
    public ApiResponse<PageResponse<ArPostingPending>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) ArPostingPendingStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ArPostingPending> result = service.list(factoryId, status, page, size);
        return ApiResponse.success("查询成功", PageResponse.of(
                result.getContent(), Math.max(page, 1), result.getSize(), result.getTotalElements()));
    }
}
