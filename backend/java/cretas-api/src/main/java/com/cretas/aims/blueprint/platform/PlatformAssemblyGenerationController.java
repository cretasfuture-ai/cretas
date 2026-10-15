package com.cretas.aims.blueprint.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.platform.AIFactoryInitRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.FactoryService;
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
    private final AIEnterpriseService ai;

    @PostMapping
    public ApiResponse<Map<String, Object>> generate(@PathVariable String factoryId,
                                                    @Valid @RequestBody AIFactoryInitRequest request) {
        var factory = factories.getFactoryById(factoryId);
        var result = ai.batchInitializeFactory(factoryId,
                request.getFactoryName() == null ? factory.getName() : request.getFactoryName(),
                request.getFactoryDescription(), request.getIndustryHint(), request.getIncludeBusinessData());
        if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
            throw new BusinessException(502, "建厂配置生成未成功，请核对 AI 服务后重试");
        }
        // Preserve new structured suggestions instead of projecting through the older
        // platform DTO, which only describes products/materials/conversions.
        return ApiResponse.success(result);
    }
}
