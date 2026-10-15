package com.cretas.aims.blueprint.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.platform.AIFactoryInitRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.TokenUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Dedicated generation entry. The historical platform initialization API stays unchanged. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/ai-initialize")
public class PlatformAssemblyGenerationController {
    private final FactoryService factories;
    private final PlatformAssemblyGenerationGateway ai;

    @PostMapping
    public ApiResponse<Map<String, Object>> generate(@PathVariable String factoryId,
                                                    @Valid @RequestBody AIFactoryInitRequest request,
                                                    HttpServletRequest httpRequest) {
        var factory = factories.getFactoryById(factoryId);
        String authorization = httpRequest.getHeader("Authorization");
        String token = authorization == null
                ? CookieAuthHelper.extractCookieValue(httpRequest, CookieAuthHelper.ACCESS_TOKEN_COOKIE)
                : TokenUtils.extractToken(authorization);
        var result = ai.generate(token,
                request.getFactoryName() == null ? factory.getName() : request.getFactoryName(),
                request.getFactoryDescription(), request.getIndustryHint(), request.getIncludeBusinessData());
        // Preserve new structured suggestions instead of projecting through the older
        // platform DTO, which only describes products/materials/conversions.
        return ApiResponse.success(result);
    }
}
