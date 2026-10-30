package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.FactoryFeatureConfig;
import com.cretas.aims.repository.FactoryFeatureConfigRepository;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature config endpoints accessible by authenticated App users.
 * GET: load module configs for the current factory.
 * PUT: admin can adjust configs (Issue #718 — @RequireRole added 2026-05-17).
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/feature-config")
@RequiredArgsConstructor
public class FeatureConfigController {

    private static final Set<String> PLATFORM_OWNED_MENU_FIELDS = Set.of("menuVisible", "showInSidebar", "disabledMenuPaths");

    private final FactoryFeatureConfigRepository featureConfigRepository;
    private final FactoryFeatureMenuWriteLock writeLock;

    @GetMapping
    public ApiResponse<List<FactoryFeatureConfig>> getAll(@PathVariable String factoryId) {
        List<FactoryFeatureConfig> configs =
                featureConfigRepository.findByFactoryIdAndDeletedAtIsNull(factoryId);
        return ApiResponse.success(configs);
    }

    @GetMapping("/{moduleId}")
    public ApiResponse<FactoryFeatureConfig> getOne(
            @PathVariable String factoryId,
            @PathVariable String moduleId) {
        return featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, moduleId)
                .map(ApiResponse::success)
                .orElseThrow(() -> new BusinessException(404, "未找到该模块的功能配置: " + moduleId)
                        .withHint("请确认模块 ID 是否正确，或该模块是否已在本工厂启用"));
    }

    @PutMapping("/{moduleId}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    @Transactional
    public ApiResponse<FactoryFeatureConfig> update(
            @PathVariable String factoryId,
            @PathVariable String moduleId,
            @RequestBody Map<String, Object> config) {
        writeLock.lock(factoryId);
        var existing = featureConfigRepository
                .findByFactoryIdAndModuleIdAndDeletedAtIsNull(factoryId, moduleId);
        if (existing.isEmpty()) {
            throw new BusinessException(400, "未找到该模块的功能配置: " + moduleId)
                    .withHint("请确认模块 ID 是否正确，或该模块是否已在本工厂启用");
        }
        FactoryFeatureConfig cfg = existing.get();
        Map<String, Object> previous = cfg.getConfig() == null ? Map.of() : cfg.getConfig();
        if (config.keySet().stream().anyMatch(PLATFORM_OWNED_MENU_FIELDS::contains)) {
            throw new BusinessException(403, "菜单显示配置只能由平台菜单管理接口修改");
        }
        // Legacy full-replacement callers must not be able to delete platform-owned
        // menu fields simply by sending an older payload without those keys.
        Map<String, Object> merged = new java.util.HashMap<>(config);
        PLATFORM_OWNED_MENU_FIELDS.forEach(key -> { if (previous.containsKey(key)) merged.put(key, previous.get(key)); });
        cfg.setConfig(merged);
        featureConfigRepository.save(cfg);
        return ApiResponse.success(cfg);
    }
}
