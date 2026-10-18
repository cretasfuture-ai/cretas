package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.ReturnRefundDTO;
import com.cretas.aims.service.inventory.ReturnRefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resolves an exact refund request from its financial ledger or voucher source. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/return-refunds")
@RequireModule("finance_ar")
@RequirePermission({"finance:read", "finance:read_write"})
@RequiredArgsConstructor
public class ReturnRefundSourceController {
    private final ReturnRefundService service;

    @GetMapping("/{requestId}")
    public ApiResponse<ReturnRefundDTO> get(@PathVariable String factoryId,
                                           @PathVariable String requestId) {
        return ApiResponse.success(service.getByRequest(factoryId, requestId));
    }
}
