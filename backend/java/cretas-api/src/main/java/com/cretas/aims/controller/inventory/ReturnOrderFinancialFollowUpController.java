package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.ReturnFinancialFollowUpDTO;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.service.inventory.ReturnOrderFinancialFollowUpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mobile/{factoryId}/return-orders")
@RequiredArgsConstructor
@RequireModule("finance_ar")
public class ReturnOrderFinancialFollowUpController {
    private final ReturnOrderFinancialFollowUpService service;

    @GetMapping("/{returnOrderId}/financial-follow-up")
    @RequirePermission({"finance:read", "finance:read_write", "finance:approve_adjustment"})
    public ApiResponse<ReturnFinancialFollowUpDTO> get(@PathVariable String factoryId,
                                                     @PathVariable String returnOrderId) {
        ReturnFinancialFollowUpDTO view = service.get(factoryId, returnOrderId);
        // Existing response masking mutates annotated fields. Only expose detached
        // presentation copies, including when OpenEntityManagerInView is enabled.
        view.setAdjustments(view.getAdjustments() == null ? java.util.List.of()
                : view.getAdjustments().stream().map(ReturnOrderFinancialFollowUpController::presentationCopy).toList());
        return ApiResponse.success(view);
    }

    @PostMapping("/{returnOrderId}/resubmit-adjustment")
    @RequirePermission("finance:read_write")
    public ApiResponse<ArApTransaction> resubmit(@PathVariable String factoryId,
                                               @PathVariable String returnOrderId,
                                               @RequestAttribute("userId") Long actorId,
                                               @Valid @RequestBody ReturnFinancialFollowUpDTO.ResubmitRequest request) {
        return ApiResponse.success("已提交待审批调整，库存和客户余额尚未改变", presentationCopy(service.resubmit(
                factoryId, returnOrderId, actorId, request.getReason())));
    }

    private static ArApTransaction presentationCopy(ArApTransaction row) {
        ArApTransaction copy = new ArApTransaction();
        org.springframework.beans.BeanUtils.copyProperties(row, copy);
        return copy;
    }
}
