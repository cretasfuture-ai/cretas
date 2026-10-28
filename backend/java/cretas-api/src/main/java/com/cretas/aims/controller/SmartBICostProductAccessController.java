package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authenticated product scope for SmartBI manufacturing-cost aggregates. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/smart-bi")
public class SmartBICostProductAccessController {

    private static final int MAX_PRODUCTS = 512;
    private static final int MAX_PRODUCT_ID_LENGTH = 191;
    private static final String RESOURCE_REF = "smartbi:cost-product-access";

    private final ProcessSecretAccessService accessService;

    public SmartBICostProductAccessController(ProcessSecretAccessService accessService) {
        this.accessService = accessService;
    }

    public record AccessRequest(@NotNull @Size(max = MAX_PRODUCTS) List<String> productIds) { }

    public record AccessResponse(String factoryId, Long userId, List<String> allowedProductIds,
                                 boolean unscopedCostAllowed) { }

    @PostMapping("/cost-product-access")
    @RequirePermission({"analytics:read"})
    public ApiResponse<AccessResponse> authorize(
            @PathVariable String factoryId,
            @Valid @RequestBody AccessRequest body,
            HttpServletRequest request) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者"));
        if (actor.id() == null || actor.id() <= 0 || factoryId == null || factoryId.isBlank()
                || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "Authenticated tenant or identity is invalid");
        }
        if (body == null || body.productIds() == null || body.productIds().size() > MAX_PRODUCTS) {
            throw new BusinessException(400, "productIds must contain at most 512 items");
        }
        List<String> requested = validateProductIds(body.productIds());

        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.size() > MAX_PRODUCTS) {
            List<String> permittedRequested = accessService.filterPermitted(
                    actor, factoryId, requested, ProcessSecretAction.VIEW_FULL, false, RESOURCE_REF);
            return ApiResponse.success(new AccessResponse(factoryId, actor.id(),
                    requestedIntersection(requested, new LinkedHashSet<>(permittedRequested)), false));
        }

        LinkedHashSet<String> scope = new LinkedHashSet<>(requested);
        scope.addAll(protectedIds);
        List<String> permitted = accessService.filterPermitted(
                actor, factoryId, new ArrayList<>(scope), ProcessSecretAction.VIEW_FULL, false, RESOURCE_REF);
        Set<String> permittedSet = new LinkedHashSet<>(permitted);
        boolean unscopedAllowed = permittedSet.containsAll(protectedIds);
        return ApiResponse.success(new AccessResponse(factoryId, actor.id(),
                requestedIntersection(requested, permittedSet), unscopedAllowed));
    }

    private static List<String> validateProductIds(List<String> productIds) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String productId : productIds) {
            if (productId == null || productId.isBlank()
                    || productId.codePointCount(0, productId.length()) > MAX_PRODUCT_ID_LENGTH
                    || productId.codePoints().anyMatch(Character::isISOControl)
                    || !unique.add(productId)) {
                throw new BusinessException(400, "productIds must be unique, nonblank identifiers of at most 191 characters");
            }
        }
        return List.copyOf(unique);
    }

    private static List<String> requestedIntersection(List<String> requested, Set<String> permitted) {
        return requested.stream().filter(permitted::contains).toList();
    }
}
