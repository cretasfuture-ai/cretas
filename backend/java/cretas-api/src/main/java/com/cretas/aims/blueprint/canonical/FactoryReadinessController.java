package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.security.RequestUserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}")
public class FactoryReadinessController {
    private final FactoryReadinessService service;
    private final CanonicalBlueprintActorAuthority authority;

    @GetMapping("/readiness")
    public ApiResponse<FactoryReadinessReport> read(@PathVariable String factoryId,
            @PathVariable String blueprintId, HttpServletRequest request) {
        authority.requireAuthorized(factoryId, RequestUserContext.currentActor(request));
        return ApiResponse.success(service.read(factoryId, blueprintId));
    }
}
