package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Status and cancellation only. Issuance is confined to verified AI confirmation. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/ai-purchase-task-grants")
public class AiPurchaseTaskGrantController {
    private final ControlledPurchaseTaskExecutionService service;
    @GetMapping("/{grantId}")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> status(@PathVariable String factoryId, @PathVariable String grantId) {
        return ApiResponse.success(service.status(factoryId, grantId));
    }
    @PostMapping("/{grantId}/revoke")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> revoke(@PathVariable String factoryId, @PathVariable String grantId,
            @Valid @RequestBody RevokeRequest request) {
        return ApiResponse.success(service.revoke(factoryId, grantId, request.expectedVersion(), request.reason()));
    }
    public record RevokeRequest(Long expectedVersion, @NotBlank @Size(max=500) String reason) { }
}
