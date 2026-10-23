package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import com.cretas.aims.service.intent.PurchaseGrantCallerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Fixed actual callers; body cannot select a tool, Skill, actor, tenant, command or confirmation. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/ai-purchase-task-grants/{grantId}")
public class PurchaseGrantCallerController {
    private final PurchaseGrantCallerService callers;

    @PostMapping("/execute-tool")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> tool(@PathVariable String factoryId,
            @PathVariable String grantId, @Valid @RequestBody AiPurchaseTaskGrantController.ExecuteLaterRequest request) {
        return ApiResponse.success(callers.executeGrant(factoryId, grantId, request.expectedVersion(), PurchaseGrantCallerService.Channel.TOOL));
    }

    @PostMapping("/execute-skill")
    public ApiResponse<ControlledPurchaseTaskExecutionService.Outcome> skill(@PathVariable String factoryId,
            @PathVariable String grantId, @Valid @RequestBody AiPurchaseTaskGrantController.ExecuteLaterRequest request) {
        return ApiResponse.success(callers.executeGrant(factoryId, grantId, request.expectedVersion(), PurchaseGrantCallerService.Channel.SKILL));
    }
}
