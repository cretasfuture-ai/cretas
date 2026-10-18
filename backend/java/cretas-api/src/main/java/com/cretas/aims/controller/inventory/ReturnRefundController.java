package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.service.inventory.ReturnRefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Four-eyes local refund registration for completed physical sales returns. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/return-orders/{returnOrderId}/refund")
@RequireModule("finance_ar")
@RequiredArgsConstructor
public class ReturnRefundController {
    private final ReturnRefundService service;

    @GetMapping
    @RequirePermission({"sales:read", "warehouse:read", "quality:read", "finance:read"})
    public ApiResponse<ReturnRefundDTO> get(@PathVariable String factoryId,
                                           @PathVariable String returnOrderId) {
        return ApiResponse.success(service.get(factoryId, returnOrderId));
    }

    @PostMapping
    @RequirePermission({"sales:read_write", "finance:read_write"})
    public ApiResponse<ReturnRefundDTO> create(@PathVariable String factoryId,
                                               @PathVariable String returnOrderId,
                                               @RequestAttribute("userId") Long actorId,
                                               @Valid @RequestBody ReturnRefundDTO.CreateRequest request) {
        service.create(factoryId, returnOrderId, request, actorId);
        return ApiResponse.success("退款申请已创建", service.get(factoryId, returnOrderId));
    }

    @PostMapping("/submit")
    @RequirePermission({"sales:read_write", "finance:read_write"})
    public ApiResponse<ReturnRefundDTO> submit(@PathVariable String factoryId,
                                               @PathVariable String returnOrderId,
                                               @RequestAttribute("userId") Long actorId) {
        service.submit(factoryId, returnOrderId, actorId);
        return ApiResponse.success("退款申请已提交", service.get(factoryId, returnOrderId));
    }

    @PostMapping("/approve")
    @RequirePermission("finance:read_write")
    public ApiResponse<ReturnRefundDTO> approve(@PathVariable String factoryId,
                                                @PathVariable String returnOrderId,
                                                @RequestAttribute("userId") Long actorId,
                                                @Valid @RequestBody(required = false) ReturnRefundDTO.ApproveRequest request) {
        service.approve(factoryId, returnOrderId, actorId, request == null ? null : request.getNote());
        return ApiResponse.success("退款申请已批准", service.get(factoryId, returnOrderId));
    }

    @PostMapping("/mark-paid")
    @RequirePermission("finance:read_write")
    public ApiResponse<ReturnRefundDTO> markPaid(@PathVariable String factoryId,
                                                 @PathVariable String returnOrderId,
                                                 @RequestAttribute("userId") Long actorId,
                                                 @Valid @RequestBody ReturnRefundDTO.MarkPaidRequest request) {
        service.markPaid(factoryId, returnOrderId, actorId, request);
        return ApiResponse.success("退款已登记付款", service.get(factoryId, returnOrderId));
    }

    @PostMapping("/reject")
    @RequirePermission("finance:read_write")
    public ApiResponse<ReturnRefundDTO> reject(@PathVariable String factoryId,
                                               @PathVariable String returnOrderId,
                                               @RequestAttribute("userId") Long actorId,
                                               @Valid @RequestBody ReturnRefundDTO.RejectRequest request) {
        service.reject(factoryId, returnOrderId, actorId, request.getReason());
        return ApiResponse.success("退款申请已拒绝", service.get(factoryId, returnOrderId));
    }

    @PostMapping("/reversal")
    @RequirePermission({"sales:read_write", "finance:read_write"})
    public ApiResponse<ReturnRefundDTO> requestReversal(@PathVariable String factoryId,
            @PathVariable String returnOrderId, @RequestAttribute("userId") Long actorId,
            @Valid @RequestBody ReturnRefundDTO.ReversalRequest request) {
        service.requestReversal(factoryId, returnOrderId, actorId, request);
        return ApiResponse.success("撤销申请已提交，等待另一位财务人员确认款项收回", service.getByRequest(factoryId, request.getRequestId()));
    }

    @PostMapping("/reversal/approve")
    @RequirePermission("finance:read_write")
    public ApiResponse<ReturnRefundDTO> approveReversal(@PathVariable String factoryId,
            @PathVariable String returnOrderId, @RequestAttribute("userId") Long actorId,
            @Valid @RequestBody ReturnRefundDTO.ReversalDecision request) {
        service.approveReversal(factoryId, returnOrderId, actorId, request);
        return ApiResponse.success("退款已撤销，应收、资金流水和凭证已同步处理", service.getByRequest(factoryId, request.getRequestId()));
    }

    @PostMapping("/reversal/reject")
    @RequirePermission("finance:read_write")
    public ApiResponse<ReturnRefundDTO> rejectReversal(@PathVariable String factoryId,
            @PathVariable String returnOrderId, @RequestAttribute("userId") Long actorId,
            @Valid @RequestBody ReturnRefundDTO.ReversalDecision request) {
        service.rejectReversal(factoryId, returnOrderId, actorId, request);
        return ApiResponse.success("撤销申请已驳回，原退款、余额和凭证保持原状态", service.getByRequest(factoryId, request.getRequestId()));
    }
}
