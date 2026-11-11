package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.bom.BomPriceAdjustmentApproveRequest;
import com.cretas.aims.dto.bom.BomPriceAdjustmentCheckRequest;
import com.cretas.aims.dto.bom.BomPriceAdjustmentRejectRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomPriceAdjustmentProposal;
import com.cretas.aims.service.bom.BomPriceAdjustmentService;
import com.cretas.aims.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * BOM 调价建议 (采购价变动 → 配方行价)。
 *
 * <p>谁能看、谁能批按权限配置, 不按岗位名 (D-S5-09, 2026-11-11):</p>
 * <ul>
 *   <li>批准 / 驳回: 专门的审批权限码 {@code approval_bom_price_adjustment:approve}, 各厂在
 *       「系统设置 → 审批规则 → 谁能审批」改授给谁; 没配置时默认 = 改动前的门槛
 *       (production / rd / finance 任一 read_write), 见 {@code PermissionServiceImpl.APPROVAL_DEFAULT_FROM_MODULES}。</li>
 *   <li>查看 (列表 / 详情): 这三个模块任一可读, 或有审批权。价格字段另由 {@code @PriceSensitive} 按采购价可见性脱敏。</li>
 * </ul>
 *
 * <p>⚠️ 已知未修 (Steve 2026-11-09 决定暂缓, 见安全待办): 批准接口的 {@code approverId} 仍取自请求体。
 * 新增的驳回接口不沿用这一做法, 驳回人取自登录身份。</p>
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom/price-adjustments")
@RequireModule("bom")
@RequiredArgsConstructor
public class BomPriceAdjustmentController {

    static final String APPROVE_PERMISSION = "approval_bom_price_adjustment:approve";

    private final BomPriceAdjustmentService service;

    @RequirePermission({"production:read", "rd:read", "finance:read", APPROVE_PERMISSION})
    @GetMapping
    public ApiResponse<Page<BomPriceAdjustmentProposal>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) BomPriceAdjustmentProposal.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(service.list(factoryId, status, pageable));
    }

    @RequirePermission({"production:read", "rd:read", "finance:read", APPROVE_PERMISSION})
    @GetMapping("/{proposalId}")
    public ApiResponse<BomPriceAdjustmentProposal> get(
            @PathVariable String factoryId,
            @PathVariable Long proposalId) {
        return ApiResponse.success(service.get(factoryId, proposalId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/check")
    public ApiResponse<List<BomPriceAdjustmentProposal>> check(
            @PathVariable String factoryId,
            @RequestBody BomPriceAdjustmentCheckRequest request) {
        return ApiResponse.success(service.generateForMaterial(
                factoryId,
                request.getMaterialTypeId(),
                request.getMaterialName(),
                request.getLatestPreTaxPrice(),
                BomPriceAdjustmentProposal.SourceType.MANUAL_CHECK,
                null,
                null));
    }

    @RequirePermission({APPROVE_PERMISSION})
    @PostMapping("/{proposalId}/approve")
    public ApiResponse<BomPriceAdjustmentProposal> approve(
            @PathVariable String factoryId,
            @PathVariable Long proposalId,
            @RequestBody BomPriceAdjustmentApproveRequest request) {
        return ApiResponse.success(service.approve(factoryId, proposalId, request.getApproverId(), request.getComment(),
                request.getExpectedUnitPrice()));
    }

    @RequirePermission({APPROVE_PERMISSION})
    @PostMapping("/{proposalId}/reject")
    public ApiResponse<BomPriceAdjustmentProposal> reject(
            @PathVariable String factoryId,
            @PathVariable Long proposalId,
            @RequestBody BomPriceAdjustmentRejectRequest request) {
        return ApiResponse.success("调价建议已驳回",
                service.reject(factoryId, proposalId, SecurityUtils.currentUserId(), request.getReason()));
    }
}
