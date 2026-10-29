package com.cretas.aims.controller;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only: the BOM recipe that was pinned to a given Workflow version, for the historical (read-only)
 * Workflow preview. Returns {@code data: null} when nothing was pinned to that version, never today's recipe.
 */
@RestController
@RequiredArgsConstructor
@RequireModule("bom")
@RequestMapping("/api/mobile/{factoryId}/bom/recipes/by-product/{productTypeId}/pinned-to-workflow")
public class BomPinnedRecipeController {

    private final BomWorkflowRevisionService service;

    @ProcessSecretGuard(action = ProcessSecretAction.VIEW_FULL, from = ProcessSecretGuard.Source.PRODUCT_PATH, name = "productTypeId")
    @GetMapping("/{workflowId}")
    public ApiResponse<BomRecipe> pinned(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @PathVariable Long workflowId) {
        return ApiResponse.success(service.findRecipePinnedToWorkflow(factoryId, productTypeId, workflowId).orElse(null));
    }
}
